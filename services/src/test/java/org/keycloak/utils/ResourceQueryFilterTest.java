package org.keycloak.utils;

import org.junit.Test;
import org.keycloak.services.util.ResourceQueryFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * @author Vaclav Muzikar <vmuzikar@redhat.com>
 */
public class ResourceQueryFilterTest {
    @Test
    public void testParsedQueries() {
        assertEquals(Map.of("foo", "bar"), parseQuery("foo:bar"));
        assertEquals(Map.of("foo", "bar baz"), parseQuery("foo:\"bar baz\""));
        assertEquals(Map.of("foo", "bar:baz"), parseQuery("foo:\"bar:baz\""));
        assertEquals(Map.of("foo", List.of("bar")), parseQuery("foo:[bar]"));
        assertEquals(Map.of("foo", Map.of("bar", "baz")), parseQuery("foo:[bar:baz]"));

        assertEquals(Map.of(
                        "foo",
                        Map.of("bar", "baz",
                                "baz", "foo",
                                "qux", "quux"),
                        "foo2",
                        List.of("qux", "quux"),
                        "foo3",
                        "bar"),
                parseQuery("foo:[bar:baz, baz:foo,qux:quux] foo2:[qux, quux] foo3:bar"));
    }

    @Test
    public void testInvalidQueries() {
        assertInvalidQuery("Invalid nested key", "foo.:bar");
        assertInvalidQuery("No support for escaping quotes yet", "foo:\"bar\\\"baz\" foo:bar");
        assertInvalidQuery("Mixing list and map", "foo:[bar:baz, qux]");
        assertInvalidQuery("Double colon", "foo:bar:baz");
        assertInvalidQuery("Multiple valid, one invalid", "foo:[bar:baz, baz:foo,qux:quux] foo2:[qux:aa, quux] foo3:bar");
        assertInvalidQuery("Comma", "foo:bar,qux:quux");
    }

    @Test
    public void testFiltering() {
        TestRepresentation rep1 = new TestRepresentation(false, "foo", 1, List.of("a", "b"), Map.of("x", "y", "x1", "y1"), new InnerRepresentation("inner1", List.of("i1", "i2")));
        TestRepresentation rep2 = new TestRepresentation(true, "bar", -1, List.of("b"), Map.of("x", "y"), null);
        TestRepresentation rep3 = new TestRepresentation(true, "bar", -1, null, null, null);

        List<TestRepresentation> representations = List.of(rep1, rep2, rep3);

        assertFilteredRepresentations(representations, "boolVal:true", rep2, rep3);
        assertFilteredRepresentations(representations, "strVal:foo", rep1);
        assertFilteredRepresentations(representations, "strVal:[foo]");
        assertFilteredRepresentations(representations, "boolVal:true intVal:-1 listVal:[b]", rep2);
        assertFilteredRepresentations(representations, "mapVal:[x:y, x1:y1]", rep1);
        assertFilteredRepresentations(representations, "mapVal:[x]", rep1, rep2);
        assertFilteredRepresentations(representations, "listVal:[a,b]", rep1);
        assertFilteredRepresentations(representations, "innerVal.innerListVal:[i1]", rep1);
        assertFilteredRepresentations(representations, "boolVal:true innerVal.innerListVal:[i1]");
        assertFilteredRepresentations(representations, "innerVal.innerStrVal:inner1", rep1);
    }

    @Test
    public void testGetterCacheEfficiency() {
    // Reset counters
    GetterCounterTestClass.getterInvocationCount = 0;
    ResourceQueryFilter.reflectionLookupCount = 0;
    GetterCounterTestClass obj = new GetterCounterTestClass("value");
    List<GetterCounterTestClass> list = List.of(obj);

    // First filter: should increment both counters
    ResourceQueryFilter<GetterCounterTestClass> filter1 = new ResourceQueryFilter<>("testField:value");
    List<GetterCounterTestClass> result1 = filter1.filterByQuery(list.stream()).toList();
    assertEquals(1, GetterCounterTestClass.getterInvocationCount);
    assertEquals(1, ResourceQueryFilter.reflectionLookupCount);

    // Second filter: should use cache, not increment reflection counter
    ResourceQueryFilter<GetterCounterTestClass> filter2 = new ResourceQueryFilter<>("testField:value");
    List<GetterCounterTestClass> result2 = filter2.filterByQuery(list.stream()).toList();
    assertEquals(2, GetterCounterTestClass.getterInvocationCount);
    assertEquals(1, ResourceQueryFilter.reflectionLookupCount);

    // Third filter: different field, should increment both counters
    ResourceQueryFilter<GetterCounterTestClass> filter3 = new ResourceQueryFilter<>("anotherField:value");
    List<GetterCounterTestClass> result3 = filter3.filterByQuery(list.stream()).toList();
    assertEquals(3, GetterCounterTestClass.getterInvocationCount);
    assertEquals(2, ResourceQueryFilter.reflectionLookupCount);

    // Fourth filter: same field as before, should use cache
    ResourceQueryFilter<GetterCounterTestClass> filter4 = new ResourceQueryFilter<>("testField:value");
    List<GetterCounterTestClass> result4 = filter4.filterByQuery(list.stream()).toList();
    assertEquals(4, GetterCounterTestClass.getterInvocationCount);
    assertEquals(2, ResourceQueryFilter.reflectionLookupCount);
    }

    public static class GetterCounterTestClass {
        public static int getterInvocationCount = 0;
        private String testField;
        private String anotherField;

        public GetterCounterTestClass(String value) {
            this.testField = value;
            this.anotherField = value;
        }

        public String getTestField() {
            getterInvocationCount++;
            return testField;
        }

        public String getAnotherField() {
            getterInvocationCount++;
            return anotherField;
        }
    }

    private void assertInvalidQuery(String message, String query) {
        assertThrows(message, IllegalArgumentException.class, () -> parseQuery(query));
    }

    private Map<String, Object> parseQuery(String query) {
        return new ResourceQueryFilter<TestRepresentation>(query).getParsedQuery();
    }

    private void assertFilteredRepresentations(List<TestRepresentation> representations, String query, TestRepresentation... expected) {
        ResourceQueryFilter<TestRepresentation> filter = new ResourceQueryFilter<>(query);
        List<TestRepresentation> filtered = filter.filterByQuery(representations.stream()).toList();
        assertEquals(Arrays.asList(expected), filtered);
    }

    public class TestRepresentation {
        private boolean boolVal;
        private String strVal;
        private int intVal;
        private List<String> listVal;
        private Map<String, String> mapVal;
        private InnerRepresentation innerVal;

        public TestRepresentation() {
        }

        public TestRepresentation(boolean boolVal, String strVal, int intVal, List<String> listVal, Map<String, String> mapVal, InnerRepresentation innerVal) {
            this.boolVal = boolVal;
            this.strVal = strVal;
            this.intVal = intVal;
            this.listVal = listVal;
            this.mapVal = mapVal;
            this.innerVal = innerVal;
        }

        public boolean isBoolVal() {
            return boolVal;
        }

        public void setBoolVal(boolean boolVal) {
            this.boolVal = boolVal;
        }

        public String getStrVal() {
            return strVal;
        }

        public void setStrVal(String strVal) {
            this.strVal = strVal;
        }

        public int getIntVal() {
            return intVal;
        }

        public void setIntVal(int intVal) {
            this.intVal = intVal;
        }

        public List<String> getListVal() {
            return listVal;
        }

        public void setListVal(List<String> listVal) {
            this.listVal = listVal;
        }

        public Map<String, String> getMapVal() {
            return mapVal;
        }

        public void setMapVal(Map<String, String> mapVal) {
            this.mapVal = mapVal;
        }

        public InnerRepresentation getInnerVal() {
            return innerVal;
        }

        public void setInnerVal(InnerRepresentation innerVal) {
            this.innerVal = innerVal;
        }

        @Override
        public String toString() {
            return "TestRepresentation{" +
                    "boolVal=" + boolVal +
                    ", strVal='" + strVal + '\'' +
                    ", intVal=" + intVal +
                    ", listVal=" + listVal +
                    ", mapVal=" + mapVal +
                    ", innerVal=" + innerVal +
                    '}';
        }
    }

    public class InnerRepresentation {
        private String innerStrVal;
        private List<String> innerListVal;

        public InnerRepresentation() {
        }

        public InnerRepresentation(String innerStrVal, List<String> innerListVal) {
            this.innerStrVal = innerStrVal;
            this.innerListVal = innerListVal;
        }

        public String getInnerStrVal() {
            return innerStrVal;
        }

        public void setInnerStrVal(String innerStrVal) {
            this.innerStrVal = innerStrVal;
        }

        public List<String> getInnerListVal() {
            return innerListVal;
        }

        public void setInnerListVal(List<String> innerListVal) {
            this.innerListVal = innerListVal;
        }

        @Override
        public String toString() {
            return "InnerRepresentation{" +
                    "innerStrVal='" + innerStrVal + '\'' +
                    ", innerListVal=" + innerListVal +
                    '}';
        }
    }
}
