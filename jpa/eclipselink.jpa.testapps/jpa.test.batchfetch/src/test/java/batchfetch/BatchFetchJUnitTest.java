package batchfetch;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.persistence.jpa.testapps.batchfetch.BatchFetchTableCreator;
import org.eclipse.persistence.jpa.testapps.batchfetch.Company;
import org.eclipse.persistence.jpa.testapps.batchfetch.Employee;
import org.eclipse.persistence.jpa.testapps.batchfetch.Record;
import org.eclipse.persistence.testing.framework.jpa.junit.JUnitTestCase;

import java.util.List;
import java.util.Objects;

public class BatchFetchJUnitTest extends JUnitTestCase {

    public BatchFetchJUnitTest() {
        super();
    }

    public BatchFetchJUnitTest(String name) {
        super(name);
    }

    public static Test suite() {
        TestSuite suite = new TestSuite();
        suite.setName("BatchFetchJunitTest");
        suite.addTest(new BatchFetchJUnitTest("testSetup"));
        suite.addTest(new BatchFetchJUnitTest("testSelectRoot"));
        suite.addTest(new BatchFetchJUnitTest("testSelectNonRoot"));
        return suite;
    }

    /**
     * The setup is done as a test, both to record its failure, and to allow execution in the server.
     */
    public void testSetup() {
        new BatchFetchTableCreator().replaceTables(JUnitTestCase.getServerSession("batchfetch"));
        EntityManager em = createEntityManager();
        createRecords(em);
    }

    public void createRecords(EntityManager em){
        try {
            beginTransaction(em);
            Company c1 = new Company(1);
            Company c2 = new Company(2);
            em.persist(c1);
            em.persist(c2);

            Employee u1 = new Employee(1, c1);
            Employee u2 = new Employee(2, c1);
            Employee u3 = new Employee(3, c2);
            em.persist(u1);
            em.persist(u2);
            em.persist(u3);

            Record r1 = new Record(1, u1);
            Record r2 = new Record(2, u2);
            Record r3 = new Record(3, u3);
            em.persist(r1);
            em.persist(r2);
            em.persist(r3);

            commitTransaction(em);
        } catch (RuntimeException ex) {
            if (isTransactionActive(em)) {
                rollbackTransaction(em);
            }
            throw ex;
        } finally {
            closeEntityManager(em);
        }
    }

    public void testSelectRoot() {
        EntityManager em = createEntityManager();
        em.getEntityManagerFactory().getCache().evictAll();

        try {
            TypedQuery<Record> q = em.createQuery("SELECT r FROM Record r", Record.class);
            List<Record> result = q.getResultList();
            assertEquals("Not all rows are selected", 3, result.size());
            List<Employee> employees = result.stream().map(Record::getEmployee).filter(Objects::nonNull).toList();
            assertEquals("Not all rows have employees", 3, employees.size());
            List<Company> companies = employees.stream().map(Employee::getCompany).filter(Objects::nonNull).toList();
            assertEquals("Not all users have companies", 3, companies.size());
        } catch (RuntimeException e) {
            closeEntityManager(em);
            throw e;
        }
    }

    public void testSelectNonRoot() {
        EntityManager em = createEntityManager();
        em.getEntityManagerFactory().getCache().evictAll();

        try {
            TypedQuery<Employee> q = em.createQuery("SELECT r.employee FROM Record r", Employee.class);
            List<Employee> result = q.getResultList();
            assertEquals("Not all rows are selected", 3, result.size());
            List<Company> companies = result.stream().map(Employee::getCompany).filter(Objects::nonNull).toList();
            assertEquals("Not all employees have companies", 3, companies.size());
        } catch (RuntimeException e) {
            closeEntityManager(em);
            throw e;
        }
    }

    @Override
    public String getPersistenceUnitName() {
        return "batchfetch";
    }
}
