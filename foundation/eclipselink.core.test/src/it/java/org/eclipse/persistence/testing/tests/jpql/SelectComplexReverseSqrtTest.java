/*
 * Copyright (c) 1998, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

// Contributors:
//     Oracle - initial API and implementation from Oracle TopLink
package org.eclipse.persistence.testing.tests.jpql;

import org.eclipse.persistence.testing.models.employee.domain.*;

public class SelectComplexReverseSqrtTest extends org.eclipse.persistence.testing.tests.jpql.SqrtTestCase {
    @Override
    public void setup() {
        setTestEmployees(getExtraEmployees());
        Employee emp1 = (Employee)getTestEmployees().firstElement();
        Employee emp2 = (Employee)getTestEmployees().lastElement();

        String ejbqlString;
        double salarySquareRoot1 = Math.sqrt(emp1.getSalary());
        double salarySquareRoot2 = Math.sqrt(emp2.getSalary());

        ejbqlString = "SELECT OBJECT(emp) FROM Employee emp WHERE ";
        ejbqlString = ejbqlString + salarySquareRoot1;
        ejbqlString = ejbqlString + " = SQRT(emp.salary)";
        ejbqlString = ejbqlString + " OR ";
        ejbqlString = ejbqlString + salarySquareRoot2;
        ejbqlString = ejbqlString + " = SQRT(emp.salary)";

        setEjbqlString(ejbqlString);
        setOriginalOject(getTestEmployees());
        super.setup();
    }
}
