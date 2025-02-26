/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001-2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2001-2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.truffleruby.parser.ast;

import org.truffleruby.language.SourceIndexLength;
import org.truffleruby.parser.ast.visitor.NodeVisitor;
import org.truffleruby.parser.parser.ParseNodeTuple;

import java.util.ArrayList;
import java.util.List;

public class HashPatternParseNode extends ParseNode {
    private final ParseNode restArg;
    private final HashParseNode keywordArgs;

    private ParseNode constant;

    public HashPatternParseNode(SourceIndexLength position, ParseNode restArg, HashParseNode keywordArgs) {
        super(position);

        this.restArg = restArg;
        this.keywordArgs = keywordArgs;
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitHashPatternNode(this);
    }

    @Override
    public List<ParseNode> childNodes() {
        return createList(restArg, keywordArgs, constant);
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.HASHPATTERNNODE;
    }

    public ParseNode getConstant() {
        return constant;
    }

    public void setConstant(ParseNode constant) {
        this.constant = constant;
    }

    // MRI: args_num in compile.c
    public int getArgumentSize() {
        return keywordArgs == null ? 0 : keywordArgs.getPairs().size();
    }

    public ParseNode getRestArg() {
        return restArg;
    }

    public boolean hasRestArg() {
        return restArg != null;
    }

    public boolean isNamedRestArg() {
        return !(restArg instanceof StarParseNode);
    }

    public boolean hasKeywordArgs() {
        return keywordArgs != null;
    }

    public HashParseNode getKeywordArgs() {
        return keywordArgs;
    }

    public List<ParseNode> getKeys() {
        List<ParseNodeTuple> pairs = keywordArgs.getPairs();
        List<ParseNode> keys = new ArrayList<>(pairs.size());

        for (ParseNodeTuple pair : pairs) {
            keys.add(pair.getKey());
        }

        return keys;
    }

    public boolean hashNamedKeywordRestArg() {
        return hasRestArg() && !(restArg instanceof StarParseNode);
    }
}
