/* ==========================================================================
 *   This program contains proprietary information which is trade secret
 *   of mm-lab GmbH, Kornwestheim, and also is protected as an unpublished
 *   work under applicable copyright laws. The program is to be retained in
 *   confidence. Any use by third parties (e.g. use as a control program,
 *   reproduction, modification and translation) is governed solely by
 *   written agreements with mm-lab GmbH.
 *
 *   mm-lab GmbH makes no representations or warranties about the suit-
 *   ability of the software, either express or implied, including but
 *   not limited to the implied warranties of merchantability, fitness
 *   for a particular purpose, or non-infringement.
 *   mm-lab GmbH shall not be liable for any damages suffered by licensee
 *   as a result of using, modifying or distributing this software or its
 *   derivatives.
 * ========================================================================= */
package org.eclipse.persistence.jpa.testapps.batchfetch;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.eclipse.persistence.annotations.BatchFetch;
import org.eclipse.persistence.annotations.BatchFetchType;

@Entity
@Table(name = "RECORD")
public class Record {
    @Id
    private long id;
    
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "EMPLOYEE_ID")
    @BatchFetch(value = BatchFetchType.IN)
    private Employee employee;

    public Record() {
    }

    public Record(long id, Employee employee) {
        this.id = id;
        this.employee = employee;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    @Override
    public String toString() {
        return "Record [id=" + id + ", employee=" + employee + "]";
    }
}
