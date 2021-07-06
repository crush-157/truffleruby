/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 * Contains code modified from JRuby's RubyConverter.java
 */
package org.truffleruby.core.encoding;

import com.oracle.truffle.api.library.CachedLibrary;
import org.jcodings.Encoding;
import org.jcodings.EncodingDB;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF16BEEncoding;
import org.jcodings.specific.UTF16LEEncoding;
import org.jcodings.specific.UTF32BEEncoding;
import org.jcodings.specific.UTF32LEEncoding;
import org.jcodings.unicode.UnicodeEncoding;
import org.jcodings.util.CaseInsensitiveBytesHash;
import org.jcodings.util.Hash;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.cast.ToEncodingNode;
import org.truffleruby.core.cast.ToRubyEncodingNode;
import org.truffleruby.core.encoding.EncodingNodesFactory.CheckRopeEncodingNodeGen;
import org.truffleruby.core.encoding.EncodingNodesFactory.GetRubyEncodingNodeGen;
import org.truffleruby.core.encoding.EncodingNodesFactory.NegotiateCompatibleEncodingNodeGen;
import org.truffleruby.core.encoding.EncodingNodesFactory.NegotiateCompatibleRopeEncodingNodeGen;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.proc.RubyProc;
import org.truffleruby.core.regexp.RubyRegexp;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringNodes.MakeStringNode;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.core.string.ImmutableRubyString;
import org.truffleruby.language.Nil;
import org.truffleruby.language.RubyContextNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.library.RubyStringLibrary;
import org.truffleruby.language.yield.CallBlockNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreModule(value = "Encoding", isClass = true)
public abstract class EncodingNodes {

    @CoreMethod(names = "ascii_compatible?")
    public abstract static class AsciiCompatibleNode extends CoreMethodArrayArgumentsNode {
        @Specialization
        protected boolean isAsciiCompatible(RubyEncoding encoding) {
            return encoding.encoding.isAsciiCompatible();
        }
    }

    public abstract static class GetRubyEncodingNode extends RubyContextNode {

        public static GetRubyEncodingNode create() {
            return GetRubyEncodingNodeGen.create();
        }

        /** This returns only the built-in RubyEncoding for the given Encoding. It will not return dummy/replicate
         * encodings that might be associated to an Encoding so this should rarely be used.
         * 
         * @param encoding
         * @return a built-in RubyEncoding */
        public abstract RubyEncoding executeGetRubyEncoding(Encoding encoding);

        @Specialization(guards = "isSameEncoding(encoding, cachedRubyEncoding)", limit = "getCacheLimit()")
        protected RubyEncoding getRubyEncodingCached(Encoding encoding,
                @Cached("getRubyEncodingUncached(encoding)") RubyEncoding cachedRubyEncoding) {
            return cachedRubyEncoding;
        }

        @Specialization(replaces = "getRubyEncodingCached")
        protected RubyEncoding getRubyEncodingUncached(Encoding encoding) {
            return Encodings.getBuiltInEncoding(encoding.getIndex());
        }

        protected boolean isSameEncoding(Encoding encoding, RubyEncoding rubyEncoding) {
            return encoding == rubyEncoding.encoding;
        }

        protected int getCacheLimit() {
            return getLanguage().options.ENCODING_LOADED_CLASSES_CACHE;
        }

    }

    public abstract static class NegotiateCompatibleRopeEncodingNode extends RubyContextNode {

        public abstract RubyEncoding executeNegotiate(Rope first, RubyEncoding firstEncoding, Rope second,
                RubyEncoding secondEncoding);

        public static NegotiateCompatibleRopeEncodingNode create() {
            return NegotiateCompatibleRopeEncodingNodeGen.create();
        }

        @Specialization(guards = "firstEncoding == secondEncoding")
        protected RubyEncoding negotiateSameEncodingUncached(
                Rope first, RubyEncoding firstEncoding, Rope second, RubyEncoding secondEncoding) {
            assert first.encoding == firstEncoding.encoding && second.encoding == secondEncoding.encoding;
            return firstEncoding;
        }

        @Specialization(
                guards = {
                        "firstEncoding != secondEncoding",
                        "first.isEmpty() == isFirstEmpty",
                        "second.isEmpty() == isSecondEmpty",
                        "cachedFirstEncoding == firstEncoding",
                        "cachedSecondEncoding == secondEncoding",
                        "codeRangeNode.execute(first) == firstCodeRange",
                        "codeRangeNode.execute(second) == secondCodeRange" },
                limit = "getCacheLimit()")
        protected RubyEncoding negotiateRopeRopeCached(
                Rope first, RubyEncoding firstEncoding, Rope second, RubyEncoding secondEncoding,
                @Cached("first.isEmpty()") boolean isFirstEmpty,
                @Cached("second.isEmpty()") boolean isSecondEmpty,
                @Cached("first.getCodeRange()") CodeRange firstCodeRange,
                @Cached("second.getCodeRange()") CodeRange secondCodeRange,
                @Cached("firstEncoding") RubyEncoding cachedFirstEncoding,
                @Cached("secondEncoding") RubyEncoding cachedSecondEncoding,
                @Cached("negotiateRopeRopeUncached(first, firstEncoding, second, secondEncoding)") RubyEncoding negotiatedEncoding,
                @Cached RopeNodes.CodeRangeNode codeRangeNode) {
            assert first.encoding == firstEncoding.encoding && second.encoding == secondEncoding.encoding;
            return negotiatedEncoding;
        }

        @Specialization(guards = "firstEncoding != secondEncoding", replaces = "negotiateRopeRopeCached")
        protected RubyEncoding negotiateRopeRopeUncached(
                Rope first, RubyEncoding firstEncoding, Rope second, RubyEncoding secondEncoding) {
            assert first.encoding == firstEncoding.encoding && second.encoding == secondEncoding.encoding;
            return compatibleEncodingForRopes(first, firstEncoding, second, secondEncoding);
        }

        @TruffleBoundary
        private static RubyEncoding compatibleEncodingForRopes(Rope firstRope, RubyEncoding firstRubyEncoding,
                Rope secondRope, RubyEncoding secondRubyEncoding) {
            // Taken from org.jruby.RubyEncoding#areCompatible.

            final Encoding firstEncoding = firstRope.getEncoding();
            final Encoding secondEncoding = secondRope.getEncoding();

            if (secondRope.isEmpty()) {
                return firstRubyEncoding;
            }
            if (firstRope.isEmpty()) {
                return firstEncoding.isAsciiCompatible() && (secondRope.getCodeRange() == CodeRange.CR_7BIT)
                        ? firstRubyEncoding
                        : secondRubyEncoding;
            }

            if (!firstEncoding.isAsciiCompatible() || !secondEncoding.isAsciiCompatible()) {
                return null;
            }

            if (firstRope.getCodeRange() != secondRope.getCodeRange()) {
                if (firstRope.getCodeRange() == CodeRange.CR_7BIT) {
                    return secondRubyEncoding;
                }
                if (secondRope.getCodeRange() == CodeRange.CR_7BIT) {
                    return firstRubyEncoding;
                }
            }
            if (secondRope.getCodeRange() == CodeRange.CR_7BIT) {
                return firstRubyEncoding;
            }
            if (firstRope.getCodeRange() == CodeRange.CR_7BIT) {
                return secondRubyEncoding;
            }

            return null;
        }

        protected int getCacheLimit() {
            return getLanguage().options.ENCODING_COMPATIBLE_QUERY_CACHE;
        }

    }

    public abstract static class NegotiateCompatibleEncodingNode extends RubyContextNode {

        @Child private RopeNodes.CodeRangeNode codeRangeNode;
        @Child private ToRubyEncodingNode getEncodingNode = ToRubyEncodingNode.create();

        public static NegotiateCompatibleEncodingNode create() {
            return NegotiateCompatibleEncodingNodeGen.create();
        }

        public abstract RubyEncoding executeNegotiate(Object first, Object second);

        @Specialization(
                guards = {
                        "getEncoding(first) == cachedEncoding",
                        "getEncoding(second) == cachedEncoding",
                        "cachedEncoding != null" },
                limit = "getCacheLimit()")
        protected RubyEncoding negotiateSameEncodingCached(Object first, Object second,
                @Cached("getEncoding(first)") RubyEncoding cachedEncoding) {
            return cachedEncoding;
        }

        @Specialization(
                guards = { "getEncoding(first) == getEncoding(second)", "getEncoding(first) != null" },
                replaces = "negotiateSameEncodingCached")
        protected RubyEncoding negotiateSameEncodingUncached(Object first, Object second) {
            return getEncoding(first);
        }

        @Specialization(guards = { "libFirst.isRubyString(first)", "libSecond.isRubyString(second)", })
        protected RubyEncoding negotiateStringStringEncoding(Object first, Object second,
                @CachedLibrary(limit = "2") RubyStringLibrary libFirst,
                @CachedLibrary(limit = "2") RubyStringLibrary libSecond,
                @Cached NegotiateCompatibleRopeEncodingNode ropeNode) {
            final RubyEncoding firstEncoding = libFirst.getEncoding(first);
            final RubyEncoding secondEncoding = libSecond.getEncoding(second);
            return ropeNode.executeNegotiate(
                    libFirst.getRope(first),
                    firstEncoding,
                    libSecond.getRope(second),
                    secondEncoding);
        }

        @Specialization(
                guards = {
                        "libFirst.isRubyString(first)",
                        "isNotRubyString(second)",
                        "getCodeRange(first, libFirst) == codeRange",
                        "getEncoding(first) == firstEncoding",
                        "getEncoding(second) == secondEncoding",
                        "firstEncoding != secondEncoding" },
                limit = "getCacheLimit()")
        protected RubyEncoding negotiateStringObjectCached(Object first, Object second,
                @CachedLibrary(limit = "2") RubyStringLibrary libFirst,
                @CachedLibrary(limit = "2") RubyStringLibrary libSecond,
                @Cached("getEncoding(first)") RubyEncoding firstEncoding,
                @Cached("getEncoding(second)") RubyEncoding secondEncoding,
                @Cached("getCodeRange(first, libFirst)") CodeRange codeRange,
                @Cached("negotiateStringObjectUncached(first, second, libFirst)") RubyEncoding negotiatedEncoding) {
            return negotiatedEncoding;
        }

        @Specialization(
                guards = {
                        "libFirst.isRubyString(first)",
                        "getEncoding(first) != getEncoding(second)",
                        "isNotRubyString(second)" },
                replaces = "negotiateStringObjectCached")
        protected RubyEncoding negotiateStringObjectUncached(Object first, Object second,
                @CachedLibrary(limit = "2") RubyStringLibrary libFirst) {
            final RubyEncoding firstEncoding = getEncoding(first);
            final RubyEncoding secondEncoding = getEncoding(second);

            if (secondEncoding == null) {
                return null;
            }

            if (!firstEncoding.encoding.isAsciiCompatible() || !secondEncoding.encoding.isAsciiCompatible()) {
                return null;
            }

            if (secondEncoding.encoding == USASCIIEncoding.INSTANCE) {
                return firstEncoding;
            }

            if (getCodeRange(first, libFirst) == CodeRange.CR_7BIT) {
                return secondEncoding;
            }

            return null;
        }

        @Specialization(
                guards = {
                        "libSecond.isRubyString(second)",
                        "getEncoding(first) != getEncoding(second)",
                        "isNotRubyString(first)" })
        protected RubyEncoding negotiateObjectString(Object first, Object second,
                @CachedLibrary(limit = "2") RubyStringLibrary libSecond) {
            return negotiateStringObjectUncached(second, first, libSecond);
        }

        @Specialization(
                guards = {
                        "firstEncoding != secondEncoding",
                        "isNotRubyString(first)",
                        "isNotRubyString(second)",
                        "firstEncoding != null",
                        "secondEncoding != null",
                        "getEncoding(first) == firstEncoding",
                        "getEncoding(second) == secondEncoding", },
                limit = "getCacheLimit()")
        protected RubyEncoding negotiateObjectObjectCached(Object first, Object second,
                @Cached("getEncoding(first)") RubyEncoding firstEncoding,
                @Cached("getEncoding(second)") RubyEncoding secondEncoding,
                @Cached("areCompatible(firstEncoding, secondEncoding)") RubyEncoding negotiatedEncoding) {

            return negotiatedEncoding;
        }

        @Specialization(
                guards = {
                        "getEncoding(first) != getEncoding(second)",
                        "isNotRubyString(first)",
                        "isNotRubyString(second)" },
                replaces = "negotiateObjectObjectCached")
        protected RubyEncoding negotiateObjectObjectUncached(Object first, Object second) {
            final RubyEncoding firstEncoding = getEncoding(first);
            final RubyEncoding secondEncoding = getEncoding(second);

            return areCompatible(firstEncoding, secondEncoding);
        }

        @TruffleBoundary
        protected static RubyEncoding areCompatible(RubyEncoding enc1, RubyEncoding enc2) {
            assert enc1 != enc2;

            if (enc1 == null || enc2 == null) {
                return null;
            }

            if (!enc1.encoding.isAsciiCompatible() || !enc2.encoding.isAsciiCompatible()) {
                return null;
            }

            if (enc2.encoding instanceof USASCIIEncoding) {
                return enc1;
            }
            if (enc1.encoding instanceof USASCIIEncoding) {
                return enc2;
            }

            return null;
        }

        protected CodeRange getCodeRange(Object string, RubyStringLibrary libString) {
            if (codeRangeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                codeRangeNode = insert(RopeNodes.CodeRangeNode.create());
            }

            return codeRangeNode.execute(libString.getRope(string));
        }

        protected RubyEncoding getEncoding(Object value) {
            return getEncodingNode.executeToEncoding(value);
        }

        protected int getCacheLimit() {
            return getLanguage().options.ENCODING_COMPATIBLE_QUERY_CACHE;
        }

    }

    @Primitive(name = "encoding_compatible?")
    public abstract static class CompatibleQueryNode extends CoreMethodArrayArgumentsNode {

        @Child private NegotiateCompatibleEncodingNode negotiateCompatibleEncodingNode = NegotiateCompatibleEncodingNode
                .create();

        public static CompatibleQueryNode create() {
            return EncodingNodesFactory.CompatibleQueryNodeFactory.create(null);
        }

        @Specialization
        protected Object isCompatible(Object first, Object second,
                @Cached ConditionProfile noNegotiatedEncodingProfile) {
            final RubyEncoding negotiatedEncoding = negotiateCompatibleEncodingNode.executeNegotiate(first, second);

            if (noNegotiatedEncodingProfile.profile(negotiatedEncoding == null)) {
                return nil;
            }

            return negotiatedEncoding;
        }
    }

    @Primitive(name = "encoding_ensure_compatible")
    public abstract static class EnsureCompatibleNode extends CoreMethodArrayArgumentsNode {

        @Child private CheckEncodingNode checkEncodingNode = CheckEncodingNode.create();

        public static EnsureCompatibleNode create() {
            return EncodingNodesFactory.EnsureCompatibleNodeFactory.create(null);
        }

        @Specialization
        protected Object ensureCompatible(Object first, Object second) {
            return checkEncodingNode.executeCheckEncoding(first, second);
        }
    }

    @CoreMethod(names = "list", onSingleton = true)
    public abstract static class ListNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubyArray list() {
            return createArray(getContext().getEncodingManager().getEncodingList());
        }
    }


    @CoreMethod(names = "locale_charmap", onSingleton = true)
    public abstract static class LocaleCharacterMapNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected ImmutableRubyString localeCharacterMap(
                @Cached GetRubyEncodingNode getRubyEncodingNode) {
            final RubyEncoding rubyEncoding = getRubyEncodingNode
                    .executeGetRubyEncoding(getContext().getEncodingManager().getLocaleEncoding());
            return rubyEncoding.name;
        }
    }

    @CoreMethod(names = "dummy?")
    public abstract static class DummyNode extends CoreMethodArrayArgumentsNode {
        @Specialization
        protected boolean isDummy(RubyEncoding encoding) {
            return encoding.dummy;
        }
    }

    @CoreMethod(names = { "name", "to_s" })
    public abstract static class ToSNode extends CoreMethodArrayArgumentsNode {
        @Specialization
        protected ImmutableRubyString toS(RubyEncoding encoding) {
            return encoding.name;
        }
    }

    @CoreMethod(names = { "__allocate__", "__layout_allocate__" }, constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @TruffleBoundary
        @Specialization
        protected Object allocate(RubyClass rubyClass) {
            throw new RaiseException(getContext(), coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

    @Primitive(name = "encoding_each_alias")
    public abstract static class EachAliasNode extends PrimitiveArrayArgumentsNode {

        @Child private CallBlockNode yieldNode = CallBlockNode.create();
        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        protected Object eachAlias(RubyProc block) {
            for (Hash.HashEntry<EncodingDB.Entry> entry : EncodingDB.getAliases().entryIterator()) {
                final CaseInsensitiveBytesHash.CaseInsensitiveBytesHashEntry<EncodingDB.Entry> e = (CaseInsensitiveBytesHash.CaseInsensitiveBytesHashEntry<EncodingDB.Entry>) entry;
                final RubyString aliasName = makeStringNode.executeMake(
                        ArrayUtils.extractRange(e.bytes, e.p, e.end),
                        Encodings.US_ASCII,
                        CodeRange.CR_7BIT);
                yieldNode.yield(
                        block,
                        aliasName,
                        getContext().getEncodingManager().getRubyEncoding(entry.value.getEncoding()));
            }
            return nil;
        }
    }

    @Primitive(name = "encoding_is_unicode")
    public abstract static class IsUnicodeNode extends PrimitiveArrayArgumentsNode {
        @Specialization
        protected boolean isUnicode(RubyEncoding encoding) {
            return encoding.encoding.isUnicode();
        }
    }

    @Primitive(name = "get_actual_encoding")
    public abstract static class GetActualEncodingPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "libString.isRubyString(string)")
        protected RubyEncoding getActualEncoding(Object string,
                @Cached GetActualEncodingNode getActualEncodingNode,
                @Cached GetRubyEncodingNode getRubyEncodingNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libString) {
            final Rope rope = libString.getRope(string);
            final Encoding actualEncoding = getActualEncodingNode.execute(rope, libString.getEncoding(string));

            return getRubyEncodingNode.executeGetRubyEncoding(actualEncoding);
        }

    }

    // Port of MRI's `get_actual_encoding`.
    public abstract static class GetActualEncodingNode extends RubyContextNode {

        protected static final Encoding UTF16Dummy = EncodingDB
                .getEncodings()
                .get(RopeOperations.encodeAsciiBytes("UTF-16"))
                .getEncoding();
        protected static final Encoding UTF32Dummy = EncodingDB
                .getEncodings()
                .get(RopeOperations.encodeAsciiBytes("UTF-32"))
                .getEncoding();

        public static GetActualEncodingNode create() {
            return EncodingNodesFactory.GetActualEncodingNodeGen.create();
        }

        public abstract Encoding execute(Rope rope, RubyEncoding rubyEncoding);

        @Specialization(guards = "!rubyEncoding.dummy")
        protected Encoding getActualEncoding(Rope rope, RubyEncoding rubyEncoding) {
            return rope.getEncoding();
        }

        @TruffleBoundary
        @Specialization(guards = "rubyEncoding.dummy")
        protected Encoding getActualEncodingDummy(Rope rope, RubyEncoding rubyEncoding) {
            final Encoding encoding = rope.getEncoding();

            if (encoding instanceof UnicodeEncoding) {
                // handle dummy UTF-16 and UTF-32 by scanning for BOM, as in MRI
                if (encoding == UTF16Dummy && rope.byteLength() >= 2) {
                    int c0 = rope.get(0) & 0xff;
                    int c1 = rope.get(1) & 0xff;

                    if (c0 == 0xFE && c1 == 0xFF) {
                        return UTF16BEEncoding.INSTANCE;
                    } else if (c0 == 0xFF && c1 == 0xFE) {
                        return UTF16LEEncoding.INSTANCE;
                    }
                    return ASCIIEncoding.INSTANCE;
                } else if (encoding == UTF32Dummy && rope.byteLength() >= 4) {
                    int c0 = rope.get(0) & 0xff;
                    int c1 = rope.get(1) & 0xff;
                    int c2 = rope.get(2) & 0xff;
                    int c3 = rope.get(3) & 0xff;

                    if (c0 == 0 && c1 == 0 && c2 == 0xFE && c3 == 0xFF) {
                        return UTF32BEEncoding.INSTANCE;
                    } else if (c3 == 0 && c2 == 0 && c1 == 0xFE && c0 == 0xFF) {
                        return UTF32LEEncoding.INSTANCE;
                    }
                    return ASCIIEncoding.INSTANCE;
                }
            }

            return encoding;
        }


    }

    @Primitive(name = "encoding_get_default_encoding")
    public abstract static class GetDefaultEncodingNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object getDefaultEncoding(Object name,
                @CachedLibrary(limit = "2") RubyStringLibrary stringLibrary) {
            final Encoding encoding = getEncoding(stringLibrary.getJavaString(name));
            if (encoding == null) {
                return nil;
            } else {
                return getContext().getEncodingManager().getRubyEncoding(encoding);
            }
        }

        @TruffleBoundary
        private Encoding getEncoding(String name) {
            switch (name) {
                case "internal":
                    return getContext().getEncodingManager().getDefaultInternalEncoding();
                case "external":
                case "filesystem":
                    return getContext().getEncodingManager().getDefaultExternalEncoding();
                case "locale":
                    return getContext().getEncodingManager().getLocaleEncoding();
                default:
                    throw CompilerDirectives.shouldNotReachHere();
            }
        }
    }

    @Primitive(name = "encoding_set_default_external")
    public abstract static class SetDefaultExternalNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected RubyEncoding setDefaultExternal(RubyEncoding encoding) {
            getContext().getEncodingManager().setDefaultExternalEncoding(encoding.encoding);
            return encoding;
        }

        @Specialization
        protected RubyEncoding noDefaultExternal(Nil encoding) {
            throw new RaiseException(
                    getContext(),
                    coreExceptions().argumentError("default external can not be nil", this));
        }

    }

    @Primitive(name = "encoding_set_default_internal")
    public abstract static class SetDefaultInternalNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected RubyEncoding setDefaultInternal(RubyEncoding encoding) {
            getContext().getEncodingManager().setDefaultInternalEncoding(encoding.encoding);
            return encoding;
        }

        @Specialization
        protected Object noDefaultInternal(Nil encoding) {
            getContext().getEncodingManager().setDefaultInternalEncoding(null);
            return nil;
        }

    }

    @Primitive(name = "encoding_enc_find_index")
    public abstract static class EncodingFindIndexNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "strings.isRubyString(nameObject)")
        protected int encodingFindIndex(Object nameObject,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            final String name = strings.getJavaString(nameObject);
            final RubyEncoding encodingObject = getContext().getEncodingManager().getRubyEncoding(name);
            return encodingObject != null ? encodingObject.index : -1;
        }

    }

    @Primitive(name = "encoding_get_object_encoding")
    public abstract static class EncodingGetObjectEncodingNode extends PrimitiveArrayArgumentsNode {

        @Child private GetRubyEncodingNode getRubyEncodingNode = EncodingNodesFactory.GetRubyEncodingNodeGen.create();

        @Specialization
        protected RubyEncoding encodingGetObjectEncodingString(RubyString object) {
            return object.encoding;
        }

        @Specialization
        protected RubyEncoding encodingGetObjectEncodingImmutableString(ImmutableRubyString object) {
            //            return object.encoding;
            return getRubyEncodingNode.executeGetRubyEncoding(object.rope.getEncoding());
        }

        @Specialization
        protected RubyEncoding encodingGetObjectEncodingSymbol(RubySymbol object) {
            return getRubyEncodingNode.executeGetRubyEncoding(object.getRope().getEncoding());
        }

        @Specialization
        protected RubyEncoding encodingGetObjectEncoding(RubyEncoding object) {
            return object;
        }

        @Specialization
        protected Object encodingGetObjectEncodingRegexp(RubyRegexp object,
                @Cached ConditionProfile hasRegexpSource) {
            final Rope regexpSource = object.source;

            if (hasRegexpSource.profile(regexpSource != null)) {
                return getRubyEncodingNode.executeGetRubyEncoding(regexpSource.getEncoding());
            } else {
                return getRubyEncodingNode.executeGetRubyEncoding(ASCIIEncoding.INSTANCE);
            }
        }

        @Fallback
        protected Object encodingGetObjectEncodingNil(Object object) {
            // TODO(CS, 26 Jan 15) something to do with __encoding__ here?
            return nil;
        }

    }

    public abstract static class EncodingCreationNode extends PrimitiveArrayArgumentsNode {

        public RubyArray setIndexOrRaiseError(String name, RubyEncoding newEncoding) {
            if (newEncoding == null) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().argumentErrorEncodingAlreadyRegistered(name, this));
            }

            final int index = newEncoding.index;
            return createArray(new Object[]{ newEncoding, index });
        }

    }

    @Primitive(name = "encoding_replicate")
    public abstract static class EncodingReplicateNode extends EncodingCreationNode {

        @Specialization(guards = "strings.isRubyString(nameObject)")
        protected RubyArray encodingReplicate(RubyEncoding object, Object nameObject,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            final String name = strings.getJavaString(nameObject);

            final RubyEncoding newEncoding = replicate(name, object);
            return setIndexOrRaiseError(name, newEncoding);
        }

        @TruffleBoundary
        private RubyEncoding replicate(String name, RubyEncoding encoding) {
            return getContext().getEncodingManager().replicateEncoding(encoding, name);
        }

    }

    @Primitive(name = "encoding_create_dummy")
    public abstract static class DummyEncodingeNode extends EncodingCreationNode {

        @Specialization(guards = "strings.isRubyString(nameObject)")
        protected RubyArray createDummyEncoding(Object nameObject,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            final String name = strings.getJavaString(nameObject);

            final RubyEncoding newEncoding = createDummy(name);
            return setIndexOrRaiseError(name, newEncoding);
        }

        @TruffleBoundary
        private RubyEncoding createDummy(String name) {
            return getContext().getEncodingManager().createDummyEncoding(name);
        }

    }

    @Primitive(name = "encoding_get_encoding_by_index", lowerFixnum = 0)
    public abstract static class GetEncodingObjectByIndexNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = { "isSingleContext()", "index == cachedIndex" }, limit = "getCacheLimit()")
        protected RubyEncoding getEncoding(int index,
                @Cached("index") int cachedIndex,
                @Cached("getContext().getEncodingManager().getRubyEncoding(index)") RubyEncoding cachedEncoding) {
            return cachedEncoding;
        }

        @Specialization(replaces = "getEncoding")
        protected RubyEncoding getEncodingUncached(int index) {
            return getContext().getEncodingManager().getRubyEncoding(index);
        }

        protected int getCacheLimit() {
            return getLanguage().options.ENCODING_LOADED_CLASSES_CACHE;
        }
    }

    @Primitive(name = "encoding_get_encoding_index")
    public abstract static class GetEncodingIndexNode extends PrimitiveArrayArgumentsNode {
        @Specialization
        protected int getIndex(RubyEncoding encoding) {
            return encoding.index;
        }
    }

    public abstract static class CheckRopeEncodingNode extends RubyContextNode {

        @Child private NegotiateCompatibleRopeEncodingNode negotiateCompatibleEncodingNode = NegotiateCompatibleRopeEncodingNode
                .create();
        @Child private GetRubyEncodingNode firstGetRubyEncodingNode = EncodingNodesFactory.GetRubyEncodingNodeGen
                .create();
        @Child private GetRubyEncodingNode secondGetRubyEncodingNode = EncodingNodesFactory.GetRubyEncodingNodeGen
                .create();

        public static CheckRopeEncodingNode create() {
            return CheckRopeEncodingNodeGen.create();
        }

        public abstract RubyEncoding executeCheckEncoding(Rope first, Rope second);

        @Specialization
        protected RubyEncoding checkEncoding(Rope first, Rope second,
                @Cached BranchProfile errorProfile) {
            final RubyEncoding firstEncoding = firstGetRubyEncodingNode.executeGetRubyEncoding(first.encoding);
            final RubyEncoding secondEncoding = secondGetRubyEncodingNode.executeGetRubyEncoding(second.encoding);

            final RubyEncoding negotiatedEncoding = negotiateCompatibleEncodingNode.executeNegotiate(
                    first,
                    firstEncoding,
                    second,
                    secondEncoding);

            if (negotiatedEncoding == null) {
                errorProfile.enter();
                throw new RaiseException(getContext(), coreExceptions().encodingCompatibilityErrorIncompatible(
                        first.getEncoding(),
                        second.getEncoding(),
                        this));
            }

            return negotiatedEncoding;
        }

    }

    public abstract static class CheckEncodingNode extends RubyContextNode {

        @Child private NegotiateCompatibleEncodingNode negotiateCompatibleEncodingNode;
        @Child private ToEncodingNode toEncodingNode;

        public static CheckEncodingNode create() {
            return EncodingNodesFactory.CheckEncodingNodeGen.create();
        }

        public abstract RubyEncoding executeCheckEncoding(Object first, Object second);

        @Specialization
        protected RubyEncoding checkEncoding(Object first, Object second,
                @Cached BranchProfile errorProfile) {
            final RubyEncoding negotiatedEncoding = executeNegotiate(first, second);

            if (negotiatedEncoding == null) {
                errorProfile.enter();
                raiseException(first, second);
            }

            return negotiatedEncoding;
        }

        private RubyEncoding executeNegotiate(Object first, Object second) {
            if (negotiateCompatibleEncodingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                negotiateCompatibleEncodingNode = insert(NegotiateCompatibleEncodingNode.create());
            }
            return negotiateCompatibleEncodingNode.executeNegotiate(first, second);
        }

        private void raiseException(Object first, Object second) {
            if (toEncodingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toEncodingNode = insert(ToEncodingNode.create());
            }

            throw new RaiseException(getContext(), coreExceptions().encodingCompatibilityErrorIncompatible(
                    toEncodingNode.executeToEncoding(first),
                    toEncodingNode.executeToEncoding(second),
                    this));
        }

    }

}
