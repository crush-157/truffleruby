/*
 * Copyright (c) 2015, 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.convert;

import com.oracle.truffle.api.memory.ByteArraySupport;
import org.truffleruby.core.format.FormatNode;
import org.truffleruby.core.format.MissingValue;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.language.Nil;

@NodeChild("bytes")
public abstract class BytesToInteger32LittleNode extends FormatNode {

    private final boolean signed;

    protected BytesToInteger32LittleNode(boolean signed) {
        this.signed = signed;
    }

    @Specialization
    protected MissingValue decode(MissingValue missingValue) {
        return missingValue;
    }

    @Specialization
    protected Object decode(Nil nil) {
        return nil;
    }

    @Specialization
    protected Object decode(byte[] bytes) {
        int value = ByteArraySupport.littleEndian().getInt(bytes, 0);
        if (signed) {
            return value;
        } else {
            return Integer.toUnsignedLong(value);
        }
    }

}
