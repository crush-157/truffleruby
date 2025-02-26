/*
 * Copyright (c) 2014, 2023 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.cast;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.strings.TruffleString;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.string.StringHelperNodes;
import org.truffleruby.core.symbol.RubySymbol;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.language.RubyBaseNodeWithExecute;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.DispatchNode;
import org.truffleruby.language.library.RubyStringLibrary;

@GenerateUncached
@NodeChild(value = "valueNode", type = RubyBaseNodeWithExecute.class)
public abstract class ToSymbolNode extends RubyBaseNodeWithExecute {

    @NeverDefault
    public static ToSymbolNode create() {
        return ToSymbolNodeGen.create(null);
    }

    public static ToSymbolNode create(RubyBaseNodeWithExecute value) {
        return ToSymbolNodeGen.create(value);
    }

    public static ToSymbolNode getUncached() {
        return ToSymbolNodeGen.getUncached();
    }

    public abstract RubySymbol execute(Object object);

    abstract RubyBaseNodeWithExecute getValueNode();

    @Specialization
    protected RubySymbol symbol(RubySymbol symbol) {
        return symbol;
    }

    @Specialization(guards = "str == cachedStr", limit = "getCacheLimit()")
    protected RubySymbol javaString(String str,
            @Cached(value = "str") String cachedStr,
            @Cached(value = "getSymbol(cachedStr)") RubySymbol rubySymbol) {
        return rubySymbol;
    }

    @Specialization(replaces = "javaString")
    protected RubySymbol javaStringUncached(String str) {
        return getSymbol(str);
    }

    @Specialization(
            guards = { "strings.isRubyString(str)", "equalNode.execute(strings, str, cachedTString, cachedEncoding)" },
            limit = "getCacheLimit()")
    protected RubySymbol rubyString(Object str,
            @Cached @Shared RubyStringLibrary strings,
            @Cached(value = "asTruffleStringUncached(str)") TruffleString cachedTString,
            @Cached(value = "strings.getEncoding(str)") RubyEncoding cachedEncoding,
            @Cached StringHelperNodes.EqualSameEncodingNode equalNode,
            @Cached(value = "getSymbol(cachedTString, cachedEncoding)") RubySymbol rubySymbol) {
        return rubySymbol;
    }

    @Specialization(guards = "strings.isRubyString(str)", replaces = "rubyString", limit = "1")
    protected RubySymbol rubyStringUncached(Object str,
            @Cached @Shared RubyStringLibrary strings) {
        return getSymbol(strings.getTString(str), strings.getEncoding(str));
    }

    @Specialization(guards = { "!isRubySymbol(object)", "!isString(object)", "isNotRubyString(object)" })
    protected RubySymbol toStr(Object object,
            @Cached BranchProfile errorProfile,
            @Cached DispatchNode toStrNode,
            @Cached @Exclusive RubyStringLibrary strings,
            @Cached ToSymbolNode toSymbolNode) {
        var coerced = toStrNode.call(
                coreLibrary().truffleTypeModule,
                "rb_convert_type",
                object,
                coreLibrary().stringClass,
                coreSymbols().TO_STR);

        if (strings.isRubyString(coerced)) {
            return toSymbolNode.execute(coerced);
        } else {
            errorProfile.enter();
            throw new RaiseException(getContext(), coreExceptions().typeErrorBadCoercion(
                    object,
                    "String",
                    "to_str",
                    coerced,
                    this));
        }
    }

    protected int getCacheLimit() {
        return getLanguage().options.DISPATCH_CACHE;
    }

    @Override
    public RubyBaseNodeWithExecute cloneUninitialized() {
        return create(getValueNode().cloneUninitialized());
    }

}
