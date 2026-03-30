/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */

package org.eclipse.lemminx.customservice.synapse.expression;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.eclipse.lemminx.customservice.synapse.expression.pojo.ExpressionError;
import org.eclipse.lemminx.util.synapse_expression.ExpressionLexer;
import org.eclipse.lemminx.util.synapse_expression.ExpressionParser;
import org.eclipse.lemminx.util.synapse_expression.ExpressionParserBaseVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Performs semantic validation on Synapse expressions after ANTLR parsing succeeds.
 * Validates function argument counts and literal argument types using the FunctionRegistry.
 */
public class SemanticExpressionValidator extends ExpressionParserBaseVisitor<Void> {

    private final List<ExpressionError> errors = new ArrayList<>();
    private final FunctionRegistry registry;

    public SemanticExpressionValidator() {
        this.registry = FunctionRegistry.getInstance();
    }

    public List<ExpressionError> getErrors() {
        return errors;
    }

    @Override
    public Void visitFunctionCall(ExpressionParser.FunctionCallContext ctx) {
        Token funcToken = ctx.FUNCTIONS().getSymbol();
        String funcName = funcToken.getText();
        int line = funcToken.getLine();
        int charPos = funcToken.getCharPositionInLine();

        // Count arguments
        List<ExpressionParser.ExpressionContext> args = ctx.expression();
        int argCount = args != null ? args.size() : 0;

        if (!registry.hasFunction(funcName)) {
            // Unknown function — skip (ANTLR grammar already restricts FUNCTIONS tokens,
            // so this shouldn't normally happen)
            return visitChildren(ctx);
        }

        List<FunctionSignature> overloads = registry.getOverloads(funcName);
        boolean arityMatch = overloads.stream().anyMatch(s -> s.getArity() == argCount);

        if (!arityMatch) {
            int minArity = registry.getMinArity(funcName);
            int maxArity = registry.getMaxArity(funcName);
            String usage = registry.getUsageString(funcName);
            String expectedStr;
            if (minArity == maxArity) {
                expectedStr = String.valueOf(minArity);
            } else {
                expectedStr = minArity + "-" + maxArity;
            }
            String message = String.format(
                    "Function '%s' expects %s argument(s) but received %d. Usage: %s",
                    funcName, expectedStr, argCount, usage);
            errors.add(new ExpressionError(line, charPos, message, funcToken, null));
        } else {
            // Arity matches — check literal argument types
            FunctionSignature matchingSig = overloads.stream()
                    .filter(s -> s.getArity() == argCount)
                    .findFirst().orElse(null);
            if (matchingSig != null && args != null) {
                validateArgumentTypes(funcName, matchingSig, args, line, charPos);
            }
        }

        // Also validate the suffix (secondary function call) if present
        ExpressionParser.FunctionCallSuffixContext suffix = ctx.functionCallSuffix();
        if (suffix != null && suffix.SECONDARY_FUNCTIONS() != null) {
            validateSecondaryFunction(suffix);
        }

        return visitChildren(ctx);
    }

    private void validateSecondaryFunction(ExpressionParser.FunctionCallSuffixContext ctx) {
        Token funcToken = ctx.SECONDARY_FUNCTIONS().getSymbol();
        String funcName = funcToken.getText();
        int line = funcToken.getLine();
        int charPos = funcToken.getCharPositionInLine();

        List<ExpressionParser.ExpressionContext> args = ctx.expression();
        int argCount = args != null ? args.size() : 0;

        if (registry.hasFunction(funcName)) {
            List<FunctionSignature> overloads = registry.getOverloads(funcName);
            boolean arityMatch = overloads.stream().anyMatch(s -> s.getArity() == argCount);
            if (!arityMatch) {
                int minArity = registry.getMinArity(funcName);
                int maxArity = registry.getMaxArity(funcName);
                String usage = registry.getUsageString(funcName);
                String expectedStr;
                if (minArity == maxArity) {
                    expectedStr = String.valueOf(minArity);
                } else {
                    expectedStr = minArity + "-" + maxArity;
                }
                String message = String.format(
                        "Function '%s' expects %s argument(s) but received %d. Usage: %s",
                        funcName, expectedStr, argCount, usage);
                errors.add(new ExpressionError(line, charPos, message, funcToken, null));
            }
        }
    }

    private void validateArgumentTypes(String funcName, FunctionSignature sig,
                                       List<ExpressionParser.ExpressionContext> args,
                                       int funcLine, int funcCharPos) {
        List<String> expectedTypes = sig.getParamTypes();
        for (int i = 0; i < args.size() && i < expectedTypes.size(); i++) {
            String expectedType = expectedTypes.get(i);
            if ("any".equals(expectedType)) {
                continue; // Accept any type
            }
            ExpressionParser.ExpressionContext argExpr = args.get(i);
            String literalType = getLiteralType(argExpr);
            if (literalType == null) {
                continue; // Not a literal — skip type checking
            }
            if (!isTypeCompatible(expectedType, literalType)) {
                Token argToken = argExpr.getStart();
                String message = String.format(
                        "Function '%s' expects argument %d to be %s but received %s literal '%s'.",
                        funcName, i + 1, expectedType, literalType, argExpr.getText());
                errors.add(new ExpressionError(argToken.getLine(), argToken.getCharPositionInLine(),
                        message, argToken, null));
            }
        }
    }

    @Override
    public Void visitFilterExpression(ExpressionParser.FilterExpressionContext ctx) {
        List<ExpressionParser.FilterComponentContext> components = ctx.filterComponent();
        if (components == null || components.size() < 2) {
            return visitChildren(ctx);
        }

        int lastOperatorIndex = -1;
        boolean lastWasComparison = false;
        boolean lastWasLogical = false;

        for (int i = 0; i < components.size(); i++) {
            ExpressionParser.FilterComponentContext comp = components.get(i);
            int opType = getFilterOperatorType(comp);

            if (opType == 1) { // comparison operator
                if (lastWasComparison && lastOperatorIndex == i - 1) {
                    addFilterWarning(comp,
                            "Possible incomplete filter expression: consecutive comparison operators without "
                                    + "an operand between them.");
                }
                lastWasComparison = true;
                lastWasLogical = false;
                lastOperatorIndex = i;
            } else if (opType == 2) { // logical binary operator (AND, OR)
                if (lastWasComparison && lastOperatorIndex == i - 1) {
                    addFilterWarning(comp,
                            "Possible incomplete filter expression: logical operator immediately after "
                                    + "comparison operator. Missing operand?");
                }
                if (lastWasLogical && lastOperatorIndex == i - 1) {
                    addFilterWarning(comp,
                            "Possible incomplete filter expression: consecutive logical operators without "
                                    + "an operand between them.");
                }
                lastWasComparison = false;
                lastWasLogical = true;
                lastOperatorIndex = i;
            } else {
                lastWasComparison = false;
                lastWasLogical = false;
            }
        }

        // Check for trailing operator
        if (lastOperatorIndex == components.size() - 1) {
            ExpressionParser.FilterComponentContext lastComp = components.get(lastOperatorIndex);
            if (lastWasComparison) {
                addFilterWarning(lastComp,
                        "Possible incomplete filter expression: ends with comparison operator without "
                                + "a right-hand operand.");
            } else if (lastWasLogical) {
                addFilterWarning(lastComp,
                        "Possible incomplete filter expression: ends with logical operator without "
                                + "a right-hand operand.");
            }
        }

        return visitChildren(ctx);
    }

    /**
     * Returns the operator type for a filter component:
     * 0 = not an operator, 1 = comparison, 2 = logical binary (AND/OR)
     */
    private int getFilterOperatorType(ExpressionParser.FilterComponentContext comp) {
        ExpressionParser.StringOrOperatorContext soo = comp.stringOrOperator();
        if (soo == null) {
            return 0;
        }
        if (soo.getChildCount() != 1 || !(soo.getChild(0) instanceof TerminalNode)) {
            return 0;
        }
        int tokenType = ((TerminalNode) soo.getChild(0)).getSymbol().getType();
        switch (tokenType) {
            case ExpressionLexer.GT:
            case ExpressionLexer.LT:
            case ExpressionLexer.GTE:
            case ExpressionLexer.LTE:
            case ExpressionLexer.EQ:
            case ExpressionLexer.NEQ:
                return 1;
            case ExpressionLexer.AND:
            case ExpressionLexer.OR:
                return 2;
            default:
                return 0;
        }
    }

    private void addFilterWarning(ExpressionParser.FilterComponentContext comp, String message) {
        Token token = comp.getStart();
        ExpressionError error = new ExpressionError(token.getLine(), token.getCharPositionInLine(),
                message, token, null);
        error.setWarning(true);
        errors.add(error);
    }

    /**
     * Returns the type of a literal expression, or null if not a simple literal.
     */
    private String getLiteralType(ExpressionParser.ExpressionContext expr) {
        // Navigate through the expression hierarchy to reach the literal
        // expression -> comparisonExpression -> logicalExpression -> arithmeticExpression -> term -> factor -> literal
        if (expr == null) return null;

        ExpressionParser.ComparisonExpressionContext comp = expr.comparisonExpression();
        if (comp == null) return null;

        List<ExpressionParser.LogicalExpressionContext> logicals = comp.logicalExpression();
        if (logicals == null || logicals.size() != 1) return null;

        ExpressionParser.LogicalExpressionContext logical = logicals.get(0);
        // logicalExpression has a single arithmeticExpression (not a list)
        ExpressionParser.ArithmeticExpressionContext arith = logical.arithmeticExpression();
        if (arith == null) return null;

        List<ExpressionParser.TermContext> terms = arith.term();
        if (terms == null || terms.size() != 1) return null;

        ExpressionParser.TermContext termCtx = terms.get(0);
        List<ExpressionParser.FactorContext> factors = termCtx.factor();
        if (factors == null || factors.size() != 1) return null;

        ExpressionParser.FactorContext factor = factors.get(0);
        ExpressionParser.LiteralContext literal = factor.literal();
        if (literal == null) return null;

        if (literal.STRING_LITERAL() != null) return "string";
        if (literal.NUMBER() != null) return "number";
        if (literal.BOOLEAN_LITERAL() != null) return "boolean";
        if (literal.arrayLiteral() != null) return "array";
        if (literal.NULL_LITERAL() != null) return "null";
        return null;
    }

    /**
     * Checks if the actual literal type is compatible with the expected parameter type.
     */
    private boolean isTypeCompatible(String expectedType, String actualType) {
        if (expectedType.equals(actualType)) return true;
        // number is compatible with integer and vice versa
        if (("number".equals(expectedType) || "integer".equals(expectedType)) &&
                ("number".equals(actualType) || "integer".equals(actualType))) {
            return true;
        }
        return false;
    }
}
