package edu.whu.tmdb.query.operations.impl;

import java.math.BigDecimal;
import java.util.List;

import edu.whu.tmdb.query.operations.Exception.ErrorList;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.TupleList;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;

/** Evaluates a HAVING expression against rows produced by GROUP BY. */
public class Having {

    public SelectResult having(PlainSelect plainSelect, SelectResult selectResult) throws TMDBException {
        Expression expression = plainSelect.getHaving();
        if (expression == null) {
            return selectResult;
        }

        TupleList filtered = new TupleList();
        for (Tuple tuple : selectResult.getTpl().tuplelist) {
            if (evaluate(expression, plainSelect, selectResult, tuple)) {
                filtered.addTuple(tuple);
            }
        }
        selectResult.setTpl(filtered);
        return selectResult;
    }

    private boolean evaluate(Expression expression, PlainSelect plainSelect,
                             SelectResult selectResult, Tuple tuple) throws TMDBException {
        if (expression instanceof Parenthesis) {
            return evaluate(((Parenthesis) expression).getExpression(), plainSelect, selectResult, tuple);
        }
        if (expression instanceof AndExpression) {
            AndExpression and = (AndExpression) expression;
            return evaluate(and.getLeftExpression(), plainSelect, selectResult, tuple)
                    && evaluate(and.getRightExpression(), plainSelect, selectResult, tuple);
        }
        if (expression instanceof OrExpression) {
            OrExpression or = (OrExpression) expression;
            return evaluate(or.getLeftExpression(), plainSelect, selectResult, tuple)
                    || evaluate(or.getRightExpression(), plainSelect, selectResult, tuple);
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression comparison = (BinaryExpression) expression;
            Object left = resolveValue(comparison.getLeftExpression(), plainSelect, selectResult, tuple);
            Object right = resolveValue(comparison.getRightExpression(), plainSelect, selectResult, tuple);
            int order = compare(left, right);
            if (expression instanceof GreaterThan) return order > 0;
            if (expression instanceof GreaterThanEquals) return order >= 0;
            if (expression instanceof MinorThan) return order < 0;
            if (expression instanceof MinorThanEquals) return order <= 0;
            if (expression instanceof EqualsTo) return order == 0;
            if (expression instanceof NotEqualsTo) return order != 0;
        }
        throw unsupported(expression);
    }

    private Object resolveValue(Expression expression, PlainSelect plainSelect,
                                SelectResult selectResult, Tuple tuple) throws TMDBException {
        if (expression instanceof Parenthesis) {
            return resolveValue(((Parenthesis) expression).getExpression(), plainSelect, selectResult, tuple);
        }
        if (expression instanceof Function) {
            int index = findFunctionIndex((Function) expression, plainSelect.getSelectItems());
            if (index >= 0) return tuple.tuple[index];
            throw unsupported(expression);
        }
        if (expression instanceof Column) {
            String name = ((Column) expression).getColumnName();
            int index = findColumnIndex(name, selectResult);
            if (index >= 0) return tuple.tuple[index];
            throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST, name);
        }
        if (expression instanceof LongValue) return ((LongValue) expression).getValue();
        if (expression instanceof DoubleValue) return ((DoubleValue) expression).getValue();
        if (expression instanceof StringValue) return ((StringValue) expression).getValue();
        if (expression instanceof NullValue) return null;
        if (expression instanceof SignedExpression) {
            SignedExpression signed = (SignedExpression) expression;
            Object value = resolveValue(signed.getExpression(), plainSelect, selectResult, tuple);
            BigDecimal number = toNumber(value);
            return signed.getSign() == '-' ? number.negate() : number;
        }
        throw unsupported(expression);
    }

    private int findFunctionIndex(Function function, List<SelectItem> selectItems) {
        String expected = normalize(function.toString());
        for (int i = 0; i < selectItems.size(); i++) {
            SelectItem item = selectItems.get(i);
            if (!(item instanceof SelectExpressionItem)) continue;
            Expression selected = ((SelectExpressionItem) item).getExpression();
            if (selected instanceof Function && normalize(selected.toString()).equals(expected)) {
                return i;
            }
        }
        return -1;
    }

    private int findColumnIndex(String name, SelectResult result) {
        for (int i = 0; i < result.getAttrname().length; i++) {
            if (name.equalsIgnoreCase(result.getAttrname()[i])
                    || name.equalsIgnoreCase(result.getAlias()[i])) {
                return i;
            }
        }
        return -1;
    }

    private int compare(Object left, Object right) throws TMDBException {
        if (left == null || right == null) {
            if (left == right) return 0;
            return left == null ? -1 : 1;
        }
        try {
            return toNumber(left).compareTo(toNumber(right));
        } catch (NumberFormatException ignored) {
            return left.toString().compareTo(right.toString());
        }
    }

    private BigDecimal toNumber(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(value.toString());
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase();
    }

    private TMDBException unsupported(Expression expression) {
        return new TMDBException(ErrorList.TYPE_IS_NOT_SUPPORTED,
                "HAVING expression " + expression);
    }
}
