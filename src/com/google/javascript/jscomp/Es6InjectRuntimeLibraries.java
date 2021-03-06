/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

/**
 * Injects JS library code that may be needed by the transpiled form of the input source code.
 *
 * <p>The intention here is to add anything that could be needed and rely on `RemoveUnusedCode` to
 * remove the parts that don't end up getting used. This pass should run before type checking so the
 * type checking code can add type information to the injected JavaScript for checking and
 * optimization purposes.
 *
 * <p>This class also reports an error if it finds getters or setters are used and the language
 * output level is too low to support them. TODO(bradfordcsmith): The getter/setter check should
 * probably be done separately in an earlier pass that only runs when the output language level is
 * ES3 and the input language level is ES5 or greater.
 */
public final class Es6InjectRuntimeLibraries extends AbstractPostOrderCallback
    implements HotSwapCompilerPass {
  private final AbstractCompiler compiler;
  private final boolean getterSetterSupported;

  // Since there's currently no Feature for Symbol, run this pass if the code has any ES6 features.
  private static final FeatureSet requiredForFeatures = FeatureSet.ES6.without(FeatureSet.ES5);

  private static final FeatureSet knownToRequireSymbol =
      FeatureSet.BARE_MINIMUM.with(Feature.FOR_OF, Feature.SPREAD_EXPRESSIONS);

  public Es6InjectRuntimeLibraries(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.getterSetterSupported =
        !FeatureSet.ES3.contains(compiler.getOptions().getOutputFeatureSet());
  }

  @Override
  public void process(Node externs, Node root) {
    FeatureSet used = FeatureSet.ES3;
    for (Node script : root.children()) {
      used = used.with(getScriptFeatures(script));
    }

    // We will need these runtime methods when we transpile, but we want the runtime
    // functions to be have JSType applied to it by the type inferrence.

    if (used.contains(Feature.FOR_OF)) {
      Es6ToEs3Util.preloadEs6RuntimeFunction(compiler, "makeIterator");
    }

    if (used.contains(Feature.SPREAD_EXPRESSIONS)) {
      Es6ToEs3Util.preloadEs6RuntimeFunction(compiler, "arrayfromiterable");
    }


    if (used.contains(Feature.GENERATORS)) {
      compiler.ensureLibraryInjected("es6/generator_engine", /* force= */ false);
    }

    boolean requiresSymbol = used.contains(knownToRequireSymbol);

    // TODO(johnlenz): remove this.  Symbol should be handled like the other polyfills.
    TranspilationPasses.processTranspile(compiler, root, requiredForFeatures, this);
  }

  private static FeatureSet getScriptFeatures(Node script) {
    FeatureSet features = NodeUtil.getFeatureSetOfScript(script);
    return features != null ? features : FeatureSet.ES3;
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    TranspilationPasses.hotSwapTranspile(compiler, scriptRoot, requiredForFeatures, this);
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getToken()) {
      // TODO(johnlenz): remove this.  Symbol should be handled like the other polyfills.
      case NAME:
        if (!n.isFromExterns() && isGlobalSymbol(t, n)) {
          initSymbolBefore(n);
        }
        break;

      case GETPROP:
        if (!n.isFromExterns()) {
          visitGetprop(t, n);
        }
        break;

      // TODO(johnlenz): this check doesn't belong here.
      case GETTER_DEF:
      case SETTER_DEF:
        if (!getterSetterSupported) {
          Es6ToEs3Util.cannotConvert(
              compiler, n, "ES5 getters/setters (consider using --language_out=ES5)");
        }
        break;
      default:
        break;
    }
  }

  /** @return Whether {@code n} is a reference to the global "Symbol" function. */
  private boolean isGlobalSymbol(NodeTraversal t, Node n) {
    if (!n.matchesQualifiedName("Symbol")) {
      return false;
    }
    Var var = t.getScope().getVar("Symbol");
    return var == null || var.isGlobal();
  }

  /** Inserts a call to $jscomp.initSymbol() before {@code n}. */
  private void initSymbolBefore(Node n) {
    compiler.ensureLibraryInjected("es6/symbol", false);
    Node statement = NodeUtil.getEnclosingStatement(n);
    Node initSymbol = IR.exprResult(IR.call(NodeUtil.newQName(compiler, "$jscomp.initSymbol")));
    statement.getParent().addChildBefore(initSymbol.useSourceInfoFromForTree(statement), statement);
    compiler.reportChangeToEnclosingScope(initSymbol);
  }

  // TODO(tbreisacher): Do this for all well-known symbols.
  private void visitGetprop(NodeTraversal t, Node n) {
    if (!n.matchesQualifiedName("Symbol.iterator")) {
      return;
    }
    if (isGlobalSymbol(t, n.getFirstChild())) {
      compiler.ensureLibraryInjected("es6/symbol", false);
      Node statement = NodeUtil.getEnclosingStatement(n);
      Node init = IR.exprResult(IR.call(NodeUtil.newQName(compiler, "$jscomp.initSymbolIterator")));
      statement.getParent().addChildBefore(init.useSourceInfoFromForTree(statement), statement);
      compiler.reportChangeToEnclosingScope(init);
    }
  }
}
