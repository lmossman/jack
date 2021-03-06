package com.rapleaf.jack.store.executors;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.rapleaf.jack.IDb;
import com.rapleaf.jack.queries.Column;
import com.rapleaf.jack.queries.GenericConstraint;
import com.rapleaf.jack.queries.GenericQuery;
import com.rapleaf.jack.queries.LimitCriterion;
import com.rapleaf.jack.queries.QueryOrder;
import com.rapleaf.jack.queries.where_operators.IWhereOperator;
import com.rapleaf.jack.store.JsConstants;
import com.rapleaf.jack.store.JsScope;
import com.rapleaf.jack.store.JsScopes;
import com.rapleaf.jack.store.exceptions.MissingScopeException;
import com.rapleaf.jack.transaction.ITransactor;

public class ScopeQueryExecutor<DB extends IDb> extends BaseExecutor<DB> {

  private final List<GenericConstraint> scopeConstraints;
  private final Map<String, List<GenericConstraint>> recordConstraints;
  private final Map<Column, QueryOrder> orderCriteria;
  private Optional<LimitCriterion> limitCriteria;

  ScopeQueryExecutor(ITransactor<DB> transactor, JsTable table, Optional<JsScope> predefinedScope, List<String> predefinedScopeNames) {
    super(transactor, table, predefinedScope, predefinedScopeNames);
    this.scopeConstraints = Lists.newArrayList();
    this.recordConstraints = Maps.newHashMap();
    this.orderCriteria = Maps.newHashMapWithExpectedSize(2);
    this.limitCriteria = Optional.empty();
  }

  public ScopeQueryExecutor<DB> whereScopeId(IWhereOperator<Long> scopeIdConstraint) {
    this.scopeConstraints.add(new GenericConstraint<>(table.idColumn.as(Long.class), scopeIdConstraint));
    return this;
  }

  public ScopeQueryExecutor<DB> whereScopeName(IWhereOperator<String> scopeNameConstraint) {
    this.scopeConstraints.add(new GenericConstraint<>(table.valueColumn, scopeNameConstraint));
    return this;
  }

  public ScopeQueryExecutor<DB> whereRecord(String key, IWhereOperator<String> valueConstraint) {
    GenericConstraint constraint = new GenericConstraint<>(table.valueColumn, valueConstraint);
    if (this.recordConstraints.containsKey(key)) {
      this.recordConstraints.get(key).add(constraint);
    } else {
      this.recordConstraints.put(key, Lists.newArrayList(constraint));
    }
    return this;
  }

  public ScopeQueryExecutor<DB> orderByScopeId(QueryOrder queryOrder) {
    this.orderCriteria.put(table.idColumn, queryOrder);
    return this;
  }

  public ScopeQueryExecutor<DB> orderByScopeName(QueryOrder queryOrder) {
    this.orderCriteria.put(table.valueColumn, queryOrder);
    return this;
  }

  public ScopeQueryExecutor<DB> limit(int limit) {
    this.limitCriteria = Optional.of(new LimitCriterion(limit));
    return this;
  }

  public ScopeQueryExecutor<DB> limit(int offset, int limit) {
    this.limitCriteria = Optional.of(new LimitCriterion(offset, limit));
    return this;
  }

  public JsScopes fetch() {
    Optional<JsScope> executionScope = getOptionalExecutionScope();
    if (executionScope.isPresent()) {
      return queryScope(transactor, table, executionScope.get(), scopeConstraints, recordConstraints, orderCriteria, limitCriteria);
    } else {
      throw new MissingScopeException(Joiner.on("/").join(predefinedScopeNames));
    }
  }

  static <DB extends IDb> JsScopes queryScope(ITransactor<DB> transactor, JsTable table, JsScope executionScope, List<GenericConstraint> scopeConstraints) {
    return queryScope(transactor, table, executionScope, scopeConstraints, Collections.emptyMap(), Collections.emptyMap(), Optional.empty());
  }

  private static <DB extends IDb> JsScopes queryScope(ITransactor<DB> transactor, JsTable table, JsScope executionScope, List<GenericConstraint> scopeConstraints, Map<String, List<GenericConstraint>> recordConstraints, Map<Column, QueryOrder> orderCriteria, Optional<LimitCriterion> limitCriteria) {
    return transactor.queryAsTransaction(db -> {
      JsScopes scopes0 = queryByScopeConstraints(db, table, executionScope, scopeConstraints);
      JsScopes scopes1 = queryByRecordConstraints(db, table, scopes0, recordConstraints);
      return queryByOrderConstraints(db, table, scopes1, orderCriteria, limitCriteria);
    });
  }

  private static JsScopes queryByScopeConstraints(IDb db, JsTable table, JsScope executionScope, List<GenericConstraint> scopeConstraints) throws IOException {
    GenericQuery query = db.createQuery()
        .from(table.table)
        .where(table.scopeColumn.as(Long.class).equalTo(executionScope.getScopeId()))
        .where(table.typeColumn.equalTo(JsConstants.SCOPE_TYPE))
        .where(table.keyColumn.equalTo(JsConstants.SCOPE_KEY))
        .select(table.idColumn, table.valueColumn);

    for (GenericConstraint constraint : scopeConstraints) {
      query.where(constraint);
    }

    List<JsScope> scopes = query.fetch().stream()
        .map(r -> new JsScope(r.get(table.idColumn), r.get(table.valueColumn)))
        .collect(Collectors.toList());

    return JsScopes.of(scopes);
  }

  private static JsScopes queryByRecordConstraints(IDb db, JsTable table, JsScopes scopes, Map<String, List<GenericConstraint>> recordConstraints) throws IOException {
    if (scopes.isEmpty() || recordConstraints.isEmpty()) {
      return scopes;
    }

    Set<Long> scopeIds = Sets.newHashSet(scopes.getScopeIds());
    for (Map.Entry<String, List<GenericConstraint>> entry : recordConstraints.entrySet()) {
      String key = entry.getKey();
      List<GenericConstraint> constraints = entry.getValue();

      GenericQuery query = db.createQuery()
          .from(table.table)
          .where(table.scopeColumn.as(Long.class).in(scopeIds))
          .where(table.keyColumn.equalTo(key))
          .select(table.scopeColumn);

      for (GenericConstraint constraint : constraints) {
        query.where(constraint);
      }

      scopeIds = query.fetch().gets(table.scopeColumn).stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    Set<Long> finalScopeIds = scopeIds;
    return JsScopes.of(scopes.getScopes().stream().filter(s -> finalScopeIds.contains(s.getScopeId())).collect(Collectors.toList()));
  }

  private static JsScopes queryByOrderConstraints(IDb db, JsTable table, JsScopes scopes, Map<Column, QueryOrder> orderCriteria, Optional<LimitCriterion> limitCriteria) throws IOException {
    if (scopes.isEmpty() || orderCriteria.isEmpty() && !limitCriteria.isPresent()) {
      return scopes;
    }

    GenericQuery query = db.createQuery()
        .from(table.table)
        .where(table.idColumn.as(Long.class).in(scopes.getScopeIds()))
        .where(table.typeColumn.equalTo(JsConstants.SCOPE_TYPE))
        .where(table.keyColumn.equalTo(JsConstants.SCOPE_KEY))
        .select(table.idColumn, table.valueColumn);

    for (Map.Entry<Column, QueryOrder> entry : orderCriteria.entrySet()) {
      query.orderBy(entry.getKey(), entry.getValue());
    }

    if (limitCriteria.isPresent()) {
      LimitCriterion limit = limitCriteria.get();
      query.limit(limit.getOffset(), limit.getNResults());
    }

    List<JsScope> orderedScopes = query.fetch().stream()
        .map(r -> new JsScope(r.get(table.idColumn), r.get(table.valueColumn)))
        .collect(Collectors.toList());

    return JsScopes.of(orderedScopes);
  }

}
