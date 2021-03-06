package com.rapleaf.jack.store.executors;

import org.junit.Test;

import com.rapleaf.jack.exception.JackRuntimeException;
import com.rapleaf.jack.queries.where_operators.JackMatchers;
import com.rapleaf.jack.store.JsScope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestScopeDeletionExecutor extends BaseExecutorTestCase {

  private boolean deletion;

  @Test(expected = JackRuntimeException.class)
  public void testNoBulkDeletion() throws Exception {
    jackStore.withinRoot().deleteScope().execute();
  }

  @Test
  public void testNoDeletion() throws Exception {
    deletion = jackStore.withinRoot().deleteScope().allowBulk().execute();
    assertTrue(deletion);
    assertTrue(jackStore.within("scope1").deleteScope().allowBulk().execute());
  }

  @Test
  public void testBulkDeletion() throws Exception {
    JsScope s1 = createScope("scope1");
    JsScope s2 = createScope("scope2");
    JsScope s11 = createScope(s1, "scope1");
    JsScope s12 = createScope(s1, "scope2");
    assertRecordCount(4);

    deletion = jackStore.within(s1).deleteScope().allowBulk().execute();
    assertTrue(deletion);
    assertRecordCount(2);
    assertEquals(2, jackStore.withinRoot().queryScope().fetch().size());

    deletion = jackStore.withinRoot().deleteScope().allowBulk().execute();
    assertTrue(deletion);
    assertRecordCount(0);
  }

  @Test
  public void testRecursiveDeletion() throws Exception {
    JsScope s1 = createScope("1");
    JsScope s2 = createScope("2");

    JsScope s11 = createScope(s1, "11");
    JsScope s12 = createScope(s1, "12");
    JsScope s21 = createScope(s2, "21");

    JsScope s121 = createScope(s12, "121");
    JsScope s122 = createScope(s12, "122");

    assertRecordCount(7);

    deletion = jackStore.withinRoot().deleteScope().allowRecursion().whereScope(JackMatchers.equalTo(s1.getScopeName())).execute();
    assertTrue(deletion);
    assertRecordCount(2);
    assertDeletedScope(s1);
    assertDeletedScope(s11);
    assertDeletedScope(s12);
    assertDeletedScope(s121);
    assertDeletedScope(s122);

    deletion = jackStore.within(s2).deleteScope().allowRecursion().whereScope(JackMatchers.equalTo(s21.getScopeName())).execute();
    assertRecordCount(1);
    assertDeletedScope(s21);
  }

  @Test
  public void testWhereConstraint() throws Exception {
    JsScope s1 = createScope("1");
    JsScope s2 = createScope("2");
    JsScope s3 = createScope("3");
    JsScope s4 = createScope("4");

    deletion = jackStore.withinRoot().deleteScope()
        .whereScope(JackMatchers.between("2", "3"))
        .execute();
    assertTrue(deletion);
    assertRecordCount(2);
    assertDeletedScope(s2);
    assertDeletedScope(s3);
  }

  private void assertRecordCount(int count) {
    assertTrue(transactor.query(db -> db.testStore().findAll().size()) == count);
  }

  private void assertDeletedScope(JsScope deletedScope) {
    assertNull(transactor.query(db -> db.testStore().find(deletedScope.getScopeId())));
  }

}
