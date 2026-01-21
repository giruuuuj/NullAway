/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.handlers.contract;

import static com.uber.nullaway.handlers.contract.ContractUtils.getAntecedent;
import static com.uber.nullaway.handlers.contract.ContractUtils.getConsequent;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.MethodAnalysisContext;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * This Handler parses @Contract-style annotations (JetBrains or any annotation with simple name
 * "Contract", plus any configured custom annotations) and tries to check if the contract is
 * followed.
 *
 * <p>Currently, it supports the case when there is only one clause in the contract. The clause of
 * the form in which all the elements of the antecedent are either of "_", "null" or "!null", and
 * the consequent is "!null" is supported. The handler checks and warns under the conditions of the
 * antecedent if the consequent is "!null" and there is a return statement with "nullable" or "null"
 * expression.
 */
public class ContractCheckHandler implements Handler {

  private final Config config;

  /** A set of value constraints in the antecedent which we can check for now. */
  private final Set<String> checkableValueConstraints = Set.of("_", "null", "!null");

  /** All known valid value constraints */
  private final Set<String> allValidValueConstraints =
      Set.of("_", "null", "!null", "true", "false");

  public ContractCheckHandler(Config config) {
    this.config = config;
  }

  /**
   * Perform checks on any {@code @Contract} annotations on the method. By default, we check for
   * syntactic well-formedness of the annotation. If {@code config.checkContracts()} is true, we
   * also check that the method body is consistent with contracts whose value constraints are one of
   * "_", "null", or "!null" in the antecedent and "!null" in the consequent.
   *
   * @param tree The AST node for the method being matched.
   * @param methodAnalysisContext The MethodAnalysisContext object
   */
  @Override
  public void onMatchMethod(MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
    Preconditions.checkNotNull(callee);
    // Check to see if this method has an @Contract annotation
    String contractString = ContractUtils.getContractString(callee, config);
    if (contractString != null) {
      // Found a contract, lets parse it.
      String[] clauses = contractString.split(";");
      if (clauses.length != 1) {
        return;
      }

      String clause = clauses[0];
      NullAway analysis = methodAnalysisContext.analysis();
      VisitorState state = methodAnalysisContext.state();
      String[] antecedent =
          getAntecedent(clause, tree, analysis, state, callee, tree.getParameters().size());
      String consequent = getConsequent(clause, tree, analysis, state, callee);

      boolean checkMethodBody = config.checkContracts();

      for (int i = 0; i < antecedent.length; ++i) {
        String valueConstraint = antecedent[i].trim();
        if (!allValidValueConstraints.contains(valueConstraint)) {
          String errorMessage =
              "Invalid @Contract annotation detected for method "
                  + callee
                  + ". It contains the following unparseable clause: "
                  + clause
                  + " (unknown value constraint: "
                  + valueConstraint
                  + ", see https://www.jetbrains.com/help/idea/contract-annotations.html).";
          state.reportMatch(
              analysis
                  .getErrorBuilder()
                  .createErrorDescription(
                      new ErrorMessage(
                          ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, errorMessage),
                      tree,
                      analysis.buildDescription(tree),
                      state,
                      null));
          checkMethodBody = false;
        } else if (!checkableValueConstraints.contains(valueConstraint)) {
          checkMethodBody = false;
        }
      }

      if (!consequent.equals("!null")) {
        checkMethodBody = false;
      }

      if (!checkMethodBody) {
        return;
      }

      // we scan the method tree for the return nodes and check the contract
      new TreePathScanner<@Nullable Void, @Nullable Void>() {
        @Override
        public @Nullable Void visitReturn(ReturnTree returnTree, @Nullable Void unused) {

          VisitorState returnState = state.withPath(getCurrentPath());
          Nullness nullness =
              analysis
                  .getNullnessAnalysis(returnState)
                  .getNullnessForContractDataflow(
                      new TreePath(returnState.getPath(), returnTree.getExpression()),
                      returnState.context);

          if (nullness == Nullness.NULLABLE || nullness == Nullness.NULL) {

            // Check if this return statement is reachable when antecedent conditions are met
            if (!isReturnReachableUnderAntecedentConditions(returnTree, returnState, antecedent, tree)) {
              // This return statement is not reachable when the antecedent conditions are true,
              // so it doesn't violate the contract
              return super.visitReturn(returnTree, null);
            }

            String errorMessage;

            // used for error message
            int nonNullAntecedentCount = 0;
            int nonNullAntecedentPosition = -1;

            for (int i = 0; i < antecedent.length; ++i) {
              String valueConstraint = antecedent[i].trim();

              if (valueConstraint.equals("!null")) {
                nonNullAntecedentCount += 1;
                nonNullAntecedentPosition = i;
              }
            }

            if (nonNullAntecedentCount == 1) {

              errorMessage =
                  "Method "
                      + callee.name
                      + " has @Contract("
                      + contractString
                      + "), but this appears to be violated, as a @Nullable value may be returned when parameter "
                      + tree.getParameters().get(nonNullAntecedentPosition).getName()
                      + " is non-null.";
            } else {
              errorMessage =
                  "Method "
                      + callee.name
                      + " has @Contract("
                      + contractString
                      + "), but this appears to be violated, as a @Nullable value may be returned "
                      + "when the contract preconditions are true.";
            }

            returnState.reportMatch(
                analysis
                    .getErrorBuilder()
                    .createErrorDescription(
                        new ErrorMessage(
                            ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, errorMessage),
                        returnTree,
                        analysis.buildDescription(returnTree),
                        returnState,
                        null));
          }
          return super.visitReturn(returnTree, null);
        }
      }.scan(state.getPath(), null);
    }
  }

  /**
   * Checks if a return statement is reachable when the antecedent conditions are met.
   * 
   * <p>This is a simplified implementation that checks if the return statement is inside
   * a conditional block that checks for parameter being null. If so, it assumes the return
   * is not reachable when the parameter is non-null.
   * 
   * @param returnTree the return statement to check
   * @param state the visitor state
   * @param antecedent the antecedent conditions from the contract
   * @param methodTree the method tree
   * @return true if the return statement is reachable when antecedent conditions are true
   */
  private boolean isReturnReachableUnderAntecedentConditions(
      ReturnTree returnTree,
      VisitorState state,
      String[] antecedent,
      MethodTree methodTree) {
    
    // For each antecedent condition, check if the return statement is reachable when it's true
    for (int i = 0; i < antecedent.length; i++) {
      String valueConstraint = antecedent[i].trim();
      
      if (valueConstraint.equals("!null")) {
        // Check if this return statement is reachable when the parameter is non-null
        if (!isReturnReachableWhenParamIsNonNull(returnTree, state, methodTree, i)) {
          return false;
        }
      }
      // We could add more checks for other constraints like "null", "true", "false" in the future
    }
    
    return true;
  }

  /**
   * Checks if a return statement is reachable when the specified parameter is non-null.
   * 
   * <p>This uses a simple heuristic: if the return statement is inside an if block
   * that checks "param == null", then we assume it's not reachable when param is non-null.
   * 
   * @param returnTree the return statement to check
   * @param state the visitor state
   * @param methodTree the method tree
   * @param paramIndex the index of the parameter to check
   * @return true if the return statement is reachable when the parameter is non-null
   */
  private boolean isReturnReachableWhenParamIsNonNull(
      ReturnTree returnTree,
      VisitorState state,
      MethodTree methodTree,
      int paramIndex) {
    
    // Get the parameter symbol
    if (paramIndex >= methodTree.getParameters().size()) {
      return true; // Invalid parameter index, assume reachable
    }
    
    var paramSymbol = ASTHelpers.getSymbol(methodTree.getParameters().get(paramIndex));
    if (paramSymbol == null) {
      return true; // Cannot get parameter symbol, assume reachable
    }
    
    // Walk up the AST to find if we're inside a conditional that checks for null
    TreePath currentPath = state.getPath();
    while (currentPath != null && currentPath.getLeaf() != returnTree) {
      Tree currentTree = currentPath.getLeaf();
      
      // Check if we're inside an if statement that checks for parameter being null
      if (currentTree instanceof com.sun.source.tree.IfTree ifTree) {
        
        // Check if the condition involves checking if the parameter is null
        if (isConditionCheckingParamNull(ifTree.getCondition(), paramSymbol)) {
          // Check if this return statement is in the "then" branch (when param is null)
          if (isReturnInThenBranch(ifTree, returnTree, currentPath)) {
            return false; // Return is only reachable when param is null
          }
        }
      }
      
      currentPath = currentPath.getParentPath();
    }
    
    return true; // Assume reachable if we can't prove otherwise
  }

  /**
   * Checks if a condition expression checks if the given parameter is null.
   */
  private boolean isConditionCheckingParamNull(
      com.sun.source.tree.ExpressionTree condition,
      Symbol paramSymbol) {
    
    // Simple check for patterns like "param == null" or "null == param"
    String conditionStr = condition.toString().trim();
    String paramName = paramSymbol.name.toString();
    
    // Check for param == null or null == param patterns
    return (conditionStr.equals(paramName + " == null") || 
            conditionStr.equals("null == " + paramName) ||
            conditionStr.equals(paramName + "!=null") ||
            conditionStr.equals("null!=" + paramName));
  }

  /**
   * Checks if the return statement is in the "then" branch of an if statement.
   */
  private boolean isReturnInThenBranch(
      com.sun.source.tree.IfTree ifTree,
      ReturnTree returnTree,
      TreePath currentPath) {
    
    // Check if the return tree is in the then branch
    TreePath thenPath = new TreePath(currentPath, ifTree.getThenStatement());
    return isTreeInPath(thenPath, returnTree);
  }

  /**
   * Checks if a specific tree is contained within a path.
   */
  private boolean isTreeInPath(TreePath path, Tree targetTree) {
    if (path == null) {
      return false;
    }
    
    if (path.getLeaf() == targetTree) {
      return true;
    }
    
    // Use a simple scanner to check all trees in the path
    TreePathScanner<java.lang.Boolean, @Nullable Void> scanner = new TreePathScanner<java.lang.Boolean, @Nullable Void>() {
      @Override
      public java.lang.Boolean scan(Tree tree, @Nullable Void p) {
        if (tree == targetTree) {
          return true;
        }
        return super.scan(tree, null);
      }
    };
    
    return scanner.scan(path.getLeaf(), null);
  }
}
