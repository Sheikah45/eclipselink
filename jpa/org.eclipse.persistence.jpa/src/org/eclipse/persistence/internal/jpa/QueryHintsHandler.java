/*******************************************************************************
 * Copyright (c) 1998, 2009 Oracle. All rights reserved.
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 
 * which accompanies this distribution. 
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Oracle - initial API and implementation from Oracle TopLink
 *     07/13/2009-2.0 Guy Pelletier 
 *       - 277039: JPA 2.0 Cache Usage Settings
 ******************************************************************************/  
package org.eclipse.persistence.internal.jpa;

import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.sql.Time;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;

import javax.persistence.CacheRetrieveMode;
import javax.persistence.CacheStoreMode;

import org.eclipse.persistence.exceptions.ConversionException;
import org.eclipse.persistence.exceptions.QueryException;


import org.eclipse.persistence.queries.DatabaseQuery;
import org.eclipse.persistence.queries.ObjectLevelReadQuery;
import org.eclipse.persistence.queries.ReadAllQuery;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.config.*;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.descriptors.invalidation.DailyCacheInvalidationPolicy;
import org.eclipse.persistence.descriptors.invalidation.TimeToLiveCacheInvalidationPolicy;
import org.eclipse.persistence.history.AsOfClause;
import org.eclipse.persistence.history.AsOfSCNClause;
import org.eclipse.persistence.internal.helper.Helper;
import org.eclipse.persistence.internal.localization.ExceptionLocalization;
import org.eclipse.persistence.internal.queries.ContainerPolicy;
import org.eclipse.persistence.internal.security.PrivilegedAccessHelper;
import org.eclipse.persistence.internal.security.PrivilegedClassForName;
import org.eclipse.persistence.internal.security.PrivilegedNewInstanceFromClass;
import org.eclipse.persistence.internal.sessions.AbstractSession;
import org.eclipse.persistence.logging.SessionLog;
import org.eclipse.persistence.mappings.ForeignReferenceMapping;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.queries.CursorPolicy;
import org.eclipse.persistence.queries.CursoredStreamPolicy;
import org.eclipse.persistence.queries.DataModifyQuery;
import org.eclipse.persistence.queries.DataReadQuery;
import org.eclipse.persistence.queries.DeleteAllQuery;
import org.eclipse.persistence.queries.DirectReadQuery;
import org.eclipse.persistence.queries.FetchGroup;
import org.eclipse.persistence.queries.InMemoryQueryIndirectionPolicy;
import org.eclipse.persistence.queries.ModifyAllQuery;
import org.eclipse.persistence.queries.ObjectBuildingQuery;
import org.eclipse.persistence.queries.QueryRedirector;
import org.eclipse.persistence.queries.ReadObjectQuery;
import org.eclipse.persistence.queries.ReadQuery;
import org.eclipse.persistence.queries.ReportQuery;
import org.eclipse.persistence.queries.ResultSetMappingQuery;
import org.eclipse.persistence.queries.ScrollableCursorPolicy;
import org.eclipse.persistence.queries.UpdateAllQuery;
import org.eclipse.persistence.queries.ValueReadQuery;

/**
 * The class processes query hints.
 * 
 * EclipseLink query hints and their values defined in org.eclipse.persistence.config package.
 * 
 * To add a new query hint:
 *   Define a new hint in QueryHints;
 *   Add a class containing hint's values if required to config package (like CacheUsage);
 *      Alternatively values defined in HintValues may be used - Refresh and BindParameters hints do that.
 *   Add an inner class to this class extending Hint corresponding to the new hint (like CacheUsageHint);
 *      The first constructor parameter is hint name; the second is default value;
 *      In constructor 
 *          provide 2-dimensional value array in case the values should be translated (currently all Hint classes do that);
 *              in case translation is not required provide a single-dimension array (no such examples yet).
 *   In inner class Hint static initializer addHint an instance of the new hint class (like addHint(new CacheUsageHint())).
 * 
 * @see QueryHints
 * @see HintValues
 * @see CacheUsage
 * @see PessimisticLock
 */
public class QueryHintsHandler {
    
    /**
     * Verifies the hints.
     * 
     * If session != null then logs a FINEST message for each hint.
     * queryName parameter used only for identifying the query in messages,
     * if it's null then "null" will be used.
     * Throws IllegalArgumentException in case the hint value is illegal.
     */
    public static void verify(Map hints, String queryName, AbstractSession session) {
        if(hints == null) {
            return;
        }
        Iterator it = hints.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry entry = (Map.Entry)it.next();
            String hintName = (String)entry.getKey();
            verify(hintName, entry.getValue(), queryName, session);
        }
    }
    
    /**
     * Verifies the hint.
     * 
     * If session != null then logs a FINEST message.
     * queryName parameter used only for identifying the query in messages,
     * if it's null then "null" will be used.
     * Throws IllegalArgumentException in case the hint value is illegal.
     */
    public static void verify(String hintName, Object hintValue, String queryName, AbstractSession session) {
        Hint.verify(hintName, shouldUseDefault(hintValue), hintValue, queryName, session);
    }
    
    /**
     * Applies the hints to the query.
     * Throws IllegalArgumentException in case the hint value is illegal.
     */
    public static DatabaseQuery apply(Map<String, Object> hints, DatabaseQuery query, ClassLoader loader) {
        if (hints == null) {
            return query;
        }
        DatabaseQuery hintQuery = query;
        for (Map.Entry<String, Object> entry : hints.entrySet()) {
            String hintName = entry.getKey();
            if (entry.getValue() instanceof Object[]) {
                Object[] values = (Object[])entry.getValue();
                for (int index = 0; index < values.length; index++) {
                    hintQuery = apply(hintName, values[index], hintQuery, loader);                    
                }
            } else {
                hintQuery = apply(hintName, entry.getValue(), hintQuery, loader);
            }
        }
        return hintQuery;
    }
    
    /**
     * Applies the hint to the query.
     * Throws IllegalArgumentException in case the hint value is illegal.
     */
    public static DatabaseQuery apply(String hintName, Object hintValue, DatabaseQuery query, ClassLoader loader) {
        return Hint.apply(hintName, shouldUseDefault(hintValue), hintValue, query, loader);
    }
    
    /**
     * Common hint value processing into an boolean value. If the hint is
     * null, false is returned. Those methods that need to handle a null hint
     * to be something other than false should not call this method.
     */
    public static boolean parseBooleanHint(Object hint) {
        if (hint == null) {
            return false;
        } else {
            return Boolean.valueOf(hint.toString());
        }
    }
    
    /**
     * Common hint value processing into an integer value. If the hint is
     * null, -1 is returned.
     */
    public static int parseIntegerHint(Object hint, String hintName) {
        if (hint == null) {
            return -1;
        } else {
            try {
                return Integer.parseInt(hint.toString());
            } catch (NumberFormatException e) {
                throw QueryException.queryHintContainedInvalidIntegerValue(hintName, hint, e);
            }
        }
    }
    
    /**
     * Empty String hintValue indicates that the default hint value
     * should be used.
     */
    protected static boolean shouldUseDefault(Object hintValue) {
        return (hintValue != null) &&  (hintValue instanceof String) && (((String)hintValue).length() == 0);
    }
    
    /**
     * Define a generic Hint.
     * Hints should subclass this and override the applyToDatabaseQuery and set the valueArray.
     */
    protected static abstract class Hint {
        static HashMap mainMap = new HashMap();
        Object[] valueArray;
        HashMap valueMap;
        String name;
        String defaultValue;
        Object defaultValueToApply;
        boolean valueToApplyMayBeNull;
        
        static {
            addHint(new BindParametersHint());
            addHint(new CacheUsageHint());
            addHint(new CacheRetrieveModeHint());
            addHint(new CacheStoreModeHint());
            addHint(new QueryTypeHint());
            addHint(new PessimisticLockHint());
            addHint(new PessimisticLockScope());
            addHint(new PessimisticLockTimeoutHint());
            addHint(new RefreshHint());
            addHint(new CascadePolicyHint());
            addHint(new BatchHint());
            addHint(new FetchHint());
            addHint(new LeftFetchHint());
            addHint(new ReadOnlyHint());
            addHint(new JDBCTimeoutHint());
            addHint(new JDBCFetchSizeHint());
            addHint(new JDBCMaxRowsHint());
            addHint(new JDBCFirstResultHint());
            addHint(new ResultCollectionTypeHint());
            addHint(new RedirectorHint());
            addHint(new QueryCacheHint());
            addHint(new QueryCacheSizeHint());
            addHint(new QueryCacheExpiryHint());
            addHint(new QueryCacheExpiryTimeOfDayHint());
            addHint(new MaintainCacheHint());
            addHint(new PrepareHint());
            addHint(new CacheStatementHint());
            addHint(new FlushHint());
            addHint(new HintHint());
            addHint(new NativeConnectionHint());
            addHint(new CursorHint());
            addHint(new CursorInitialSizeHint());
            addHint(new CursorPageSizeHint());
            addHint(new ScrollableCursorHint());
            addHint(new CursorSizeHint());
            addHint(new FetchGroupHint());
            addHint(new FetchGroupNameHint());
            addHint(new FetchGroupDefaultHint());
            addHint(new FetchGroupAttributeHint());
            addHint(new ExclusiveHint());
            addHint(new InheritanceJoinHint());
            addHint(new AsOfHint());
            addHint(new AsOfSCNHint());
            addHint(new ResultTypeHint());
            addHint(new ResultSetTypeHint());
            addHint(new ResultSetConcurrencyHint());
            addHint(new IndirectionPolicyHint());
        }
        
        Hint(String name, String defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }

        abstract DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader);
                
        static void verify(String hintName, boolean shouldUseDefault, Object hintValue, String queryName, AbstractSession session) {
            Hint hint = (Hint)mainMap.get(hintName);
            if(hint == null) {
                if(session != null) {
                    session.log(SessionLog.FINEST, SessionLog.QUERY, "unknown_query_hint", new Object[]{getPrintValue(queryName), hintName});
                }
                return;
            }
                                    
            hint.verify(hintValue, shouldUseDefault, queryName, session);
        }
        
        void verify(Object hintValue, boolean shouldUseDefault, String queryName, AbstractSession session) {
            if(shouldUseDefault) {
                hintValue = defaultValue;
            }
            if(session != null) {
                session.log(SessionLog.FINEST, SessionLog.QUERY, "query_hint", new Object[]{getPrintValue(queryName), name, getPrintValue(hintValue)});
            }
            if(!shouldUseDefault && valueMap != null && !valueMap.containsKey(getUpperCaseString(hintValue))) {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-query-hint-value",new Object[]{getPrintValue(queryName), name, getPrintValue(hintValue)}));
            }
        }
        
        static DatabaseQuery apply(String hintName, boolean shouldUseDefault, Object hintValue, DatabaseQuery query, ClassLoader loader) {
            Hint hint = (Hint)mainMap.get(hintName);
            if (hint == null) {
                // unknown hint name - silently ignored.
                return query;
            }
            
            return hint.apply(hintValue, shouldUseDefault, query, loader);
        }
        
        DatabaseQuery apply(Object hintValue, boolean shouldUseDefault, DatabaseQuery query, ClassLoader loader) {
            Object valueToApply = hintValue;
            if (shouldUseDefault) {
                valueToApply = defaultValueToApply;
            } else {
                if( valueMap != null) {
                    String key = getUpperCaseString(hintValue);
                    valueToApply = valueMap.get(key);
                    if (valueToApply == null) {
                        boolean wrongKey = true;
                        if (valueToApplyMayBeNull) {
                            wrongKey = !valueMap.containsKey(key);
                        }
                        if (wrongKey) {
                            throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-query-hint-value",new Object[]{getQueryId(query), name, getPrintValue(hintValue)}));
                        }
                    }
                }
            }
            return applyToDatabaseQuery(valueToApply, query, loader);
        }

        static String getQueryId(DatabaseQuery query) {
            String queryId = query.getName();
            if(queryId == null) {
                queryId = query.getEJBQLString();
            }
            return getPrintValue(queryId);
        }
        
        static String getPrintValue(Object hintValue) {
            return hintValue != null ? hintValue.toString() : "null";
        }
    
        static String getUpperCaseString(Object hintValue) {
            return hintValue != null ? hintValue.toString().toUpperCase() : null;
        }

        static Class loadClass(String className, DatabaseQuery query, ClassLoader loader) throws QueryException {
            try {
                if (PrivilegedAccessHelper.shouldUsePrivilegedAccess()) {
                    try {
                        return (Class)AccessController.doPrivileged(new PrivilegedClassForName(className, true, loader));
                    } catch (PrivilegedActionException exception) {
                        throw QueryException.classNotFoundWhileUsingQueryHint(query, className, exception.getException());
                    }
                } else {
                    return PrivilegedAccessHelper.getClassForName(className, true, loader);
                }
            } catch (ClassNotFoundException exception){
                throw QueryException.classNotFoundWhileUsingQueryHint(query, className, exception);
            }
        }
        
        static Object newInstance(Class theClass, DatabaseQuery query, String hint) {
            try {
                if (PrivilegedAccessHelper.shouldUsePrivilegedAccess()){
                    return AccessController.doPrivileged(new PrivilegedNewInstanceFromClass(theClass));
                } else {
                    return PrivilegedAccessHelper.newInstanceFromClass(theClass);
                }
            } catch (Exception exception) {
                throw QueryException.errorInstantiatedClassForQueryHint(exception, query, theClass, hint);                
            }
        }

        void initialize() {
            if(valueArray != null) {
                valueMap = new HashMap(valueArray.length);
                if(valueArray instanceof Object[][]) {
                    Object[][] valueArray2 = (Object[][])valueArray;
                    for(int i=0; i<valueArray2.length; i++) {
                        valueMap.put(getUpperCaseString(valueArray2[i][0]), valueArray2[i][1]);
                        if(valueArray2[i][1] == null) {
                            valueToApplyMayBeNull = true;
                        }
                    }
                } else {
                    for(int i=0; i<valueArray.length; i++) {
                        valueMap.put(getUpperCaseString(valueArray[i]), valueArray[i]);
                        if(valueArray[i] == null) {
                            valueToApplyMayBeNull = true;
                        }
                    }
                }
                defaultValueToApply = valueMap.get(defaultValue.toUpperCase());
            }
        }
        
        static void addHint(Hint hint) {
            hint.initialize();
            mainMap.put(hint.name, hint);
        }
    }

    protected static class BindParametersHint extends Hint {
        BindParametersHint() {
            super(QueryHints.BIND_PARAMETERS, HintValues.PERSISTENCE_UNIT_DEFAULT);
            valueArray = new Object[][] { 
                {HintValues.PERSISTENCE_UNIT_DEFAULT, null},
                {HintValues.TRUE, Boolean.TRUE},
                {HintValues.FALSE, Boolean.FALSE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (valueToApply == null) {
                query.ignoreBindAllParameters();
            } else {
                query.setShouldBindAllParameters(((Boolean)valueToApply).booleanValue());
            }
            return query;
        }
    }
    
    protected static class CacheRetrieveModeHint extends Hint {
        CacheRetrieveModeHint() {
            super(QueryHints.CACHE_RETRIEVE_MODE, CacheRetrieveMode.USE.name());
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                if (valueToApply.equals(CacheRetrieveMode.BYPASS) || valueToApply.equals(CacheRetrieveMode.BYPASS.name())) {
                    query.retrieveBypassCache();
                }

                // CacheRetrieveMode.USE will use the EclipseLink default of 
                // shouldCheckDescriptorForCacheUsage which in most cases is CheckCacheByPrimaryKey.
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }
    
    protected static class CacheStoreModeHint extends Hint {
        CacheStoreModeHint() {
            super(QueryHints.CACHE_STORE_MODE, CacheStoreMode.USE.name());
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (valueToApply.equals(CacheStoreMode.BYPASS) || valueToApply.equals(CacheStoreMode.BYPASS.name())) {
                query.storeBypassCache();
            } else if (valueToApply.equals(CacheStoreMode.REFRESH) || valueToApply.equals(CacheStoreMode.REFRESH.name())) {
                if (query.isObjectLevelReadQuery()) {
                    ((ObjectLevelReadQuery) query).refreshIdentityMapResult();
                } else {
                    throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
                }
            } 
            
            // CacheStoreMode.USE will use the EclipseLink default maintainCache.
            
            return query;
        }
    }

    /**
     * Configure the cache usage of the query.
     * As many of the usages require a ReadObjectQuery, the hint may also require to change the query type.
     */
    protected static class CacheUsageHint extends Hint {
        CacheUsageHint() {
            super(QueryHints.CACHE_USAGE, CacheUsage.DEFAULT);
            valueArray = new Object[][] {
                {CacheUsage.UseEntityDefault, ObjectLevelReadQuery.UseDescriptorSetting},
                {CacheUsage.DoNotCheckCache, ObjectLevelReadQuery.DoNotCheckCache},
                {CacheUsage.CheckCacheByExactPrimaryKey, ObjectLevelReadQuery.CheckCacheByExactPrimaryKey},
                {CacheUsage.CheckCacheByPrimaryKey, ObjectLevelReadQuery.CheckCacheByPrimaryKey},
                {CacheUsage.CheckCacheThenDatabase, ObjectLevelReadQuery.CheckCacheThenDatabase},
                {CacheUsage.CheckCacheOnly, ObjectLevelReadQuery.CheckCacheOnly},
                {CacheUsage.ConformResultsInUnitOfWork, ObjectLevelReadQuery.ConformResultsInUnitOfWork},
                {CacheUsage.NoCache, ModifyAllQuery.NO_CACHE},
                {CacheUsage.Invalidate, ModifyAllQuery.INVALIDATE_CACHE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                int cacheUsage = ((Integer)valueToApply).intValue();
                ((ObjectLevelReadQuery)query).setCacheUsage(cacheUsage);
                if (cacheUsage == ObjectLevelReadQuery.CheckCacheByExactPrimaryKey
                        || cacheUsage == ObjectLevelReadQuery.CheckCacheByPrimaryKey
                        || cacheUsage == ObjectLevelReadQuery.CheckCacheThenDatabase) {
                    ReadObjectQuery newQuery = new ReadObjectQuery();
                    newQuery.copyFromQuery(query);
                    return newQuery;
                }
            } else if (query.isModifyAllQuery()) {
                int cacheUsage = ((Integer)valueToApply).intValue();
                ((ModifyAllQuery)query).setCacheUsage(cacheUsage);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    protected static class CascadePolicyHint extends Hint {
        CascadePolicyHint() {
            super(QueryHints.REFRESH_CASCADE, CascadePolicy.DEFAULT);
            valueArray = new Object[][] {
                {CascadePolicy.NoCascading, DatabaseQuery.NoCascading},
                {CascadePolicy.CascadePrivateParts, DatabaseQuery.CascadePrivateParts},
                {CascadePolicy.CascadeAllParts, DatabaseQuery.CascadeAllParts},
                {CascadePolicy.CascadeByMapping, DatabaseQuery.CascadeByMapping}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setCascadePolicy((Integer)valueToApply);
            return query;
        }
    }

    /**
     * Configure the type of the query.
     */
    protected static class QueryTypeHint extends Hint {
        QueryTypeHint() {
            super(QueryHints.QUERY_TYPE, QueryType.DEFAULT);
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (valueToApply.equals(QueryType.DEFAULT)) {
                return query;
            }
            // Allows an query type, or a custom query class.
            DatabaseQuery newQuery = query;
            if (valueToApply.equals(QueryType.ReadAll)) {
                newQuery = new ReadAllQuery();
            } else if (valueToApply.equals(QueryType.ReadObject)) {
                newQuery = new ReadObjectQuery();
            } else if (valueToApply.equals(QueryType.Report)) {
                newQuery = new ReportQuery();
                if (query.isObjectLevelReadQuery()) {
                    ((ReportQuery)newQuery).addAttribute("root", ((ReportQuery)newQuery).getExpressionBuilder());
                }
            } else if (valueToApply.equals(QueryType.ResultSetMapping)) {
                newQuery = new ResultSetMappingQuery();
            } else if (valueToApply.equals(QueryType.UpdateAll)) {
                newQuery = new UpdateAllQuery();
            } else if (valueToApply.equals(QueryType.DeleteAll)) {
                newQuery = new DeleteAllQuery();
            } else if (valueToApply.equals(QueryType.DataModify)) {
                newQuery = new DataModifyQuery();
            } else if (valueToApply.equals(QueryType.DataRead)) {
                newQuery = new DataReadQuery();
            } else if (valueToApply.equals(QueryType.DirectRead)) {
                newQuery = new DirectReadQuery();
            } else if (valueToApply.equals(QueryType.ValueRead)) {
                newQuery = new ValueReadQuery();
            } else {
                Class queryClass = loadClass((String)valueToApply, query, loader);
                newQuery = (DatabaseQuery)newInstance(queryClass, query, QueryHints.QUERY_TYPE);
            }
            newQuery.copyFromQuery(query);
            return newQuery;
        }
    }
    
    protected static class PessimisticLockHint extends Hint {
        PessimisticLockHint() {
            super(QueryHints.PESSIMISTIC_LOCK, PessimisticLock.DEFAULT);
            valueArray = new Object[][] {
                {PessimisticLock.NoLock, ObjectLevelReadQuery.NO_LOCK},
                {PessimisticLock.Lock, ObjectLevelReadQuery.LOCK},
                {PessimisticLock.LockNoWait, ObjectLevelReadQuery.LOCK_NOWAIT}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectBuildingQuery()) {
                ((ObjectBuildingQuery)query).setLockMode(((Short)valueToApply).shortValue());
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    protected static class PessimisticLockScope extends Hint {
        PessimisticLockScope() {
            super(QueryHints.PESSIMISTIC_LOCK_SCOPE, javax.persistence.PessimisticLockScope.NORMAL.name());
            valueArray = new Object[] { 
                javax.persistence.PessimisticLockScope.NORMAL.name(),
                javax.persistence.PessimisticLockScope.EXTENDED.name()
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                boolean shouldExtend = valueToApply.equals(javax.persistence.PessimisticLockScope.EXTENDED.name());  
                ObjectLevelReadQuery olrQuery = (ObjectLevelReadQuery)query;
                olrQuery.setShouldExtendPessimisticLockScope(shouldExtend);
                if(shouldExtend) {
                    olrQuery.extendPessimisticLockScope();
                }
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class PessimisticLockTimeoutHint extends Hint {
        PessimisticLockTimeoutHint() {
            super(QueryHints.PESSIMISTIC_LOCK_TIMEOUT, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {                    
                ((ObjectLevelReadQuery) query).setWaitTimeout(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.PESSIMISTIC_LOCK_TIMEOUT));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }
    
    protected static class RefreshHint extends Hint {
        RefreshHint() {
            super(QueryHints.REFRESH, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectBuildingQuery()) {
                ((ObjectBuildingQuery)query).setShouldRefreshIdentityMapResult(((Boolean)valueToApply).booleanValue());
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class ResultTypeHint extends Hint {
        ResultTypeHint() {
            super(QueryHints.RESULT_TYPE, ResultType.DEFAULT);
            valueArray = new Object[][] { 
                {ResultType.Map, ResultType.Map},
                {ResultType.Array, ResultType.Array},
                {ResultType.Value, ResultType.Value},
                {ResultType.Attribute, ResultType.Attribute}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isDataReadQuery()) {
                if (valueToApply == ResultType.Map) {
                    ((DataReadQuery)query).setResultType(DataReadQuery.MAP);
                } else if (valueToApply == ResultType.Array) {
                    ((DataReadQuery)query).setResultType(DataReadQuery.ARRAY);
                } else if (valueToApply == ResultType.Attribute) {
                    ((DataReadQuery)query).setResultType(DataReadQuery.ATTRIBUTE);
                } else if (valueToApply == ResultType.Value) {
                    ((DataReadQuery)query).setResultType(DataReadQuery.VALUE);
                }
            } else if (query.isReportQuery()) {
                if (valueToApply == ResultType.Map) {
                    ((ReportQuery)query).setReturnType(ReportQuery.ShouldReturnReportResult);
                } else if (valueToApply == ResultType.Array) {
                    ((ReportQuery)query).setReturnType(ReportQuery.ShouldReturnArray);
                } else if (valueToApply == ResultType.Attribute) {
                    ((ReportQuery)query).setReturnType(ReportQuery.ShouldReturnSingleAttribute);
                } else if (valueToApply == ResultType.Value) {
                    ((ReportQuery)query).setReturnType(ReportQuery.ShouldReturnSingleValue);
                }
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class IndirectionPolicyHint extends Hint {
        IndirectionPolicyHint() {
            super(QueryHints.INDIRECTION_POLICY, CacheUsageIndirectionPolicy.DEFAULT);
            valueArray = new Object[][] { 
                {CacheUsageIndirectionPolicy.Conform, InMemoryQueryIndirectionPolicy.SHOULD_IGNORE_EXCEPTION_RETURN_CONFORMED},
                {CacheUsageIndirectionPolicy.NotConform, InMemoryQueryIndirectionPolicy.SHOULD_IGNORE_EXCEPTION_RETURN_CONFORMED},
                {CacheUsageIndirectionPolicy.Trigger, InMemoryQueryIndirectionPolicy.SHOULD_TRIGGER_INDIRECTION},
                {CacheUsageIndirectionPolicy.Exception, InMemoryQueryIndirectionPolicy.SHOULD_THROW_INDIRECTION_EXCEPTION}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery) query).setInMemoryQueryIndirectionPolicyState((Integer)valueToApply);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class ResultSetTypeHint extends Hint {
        ResultSetTypeHint() {
            super(QueryHints.RESULT_SET_TYPE, ResultSetType.DEFAULT);
            valueArray = new Object[][] { 
                {ResultSetType.Forward, ScrollableCursorPolicy.FETCH_FORWARD},
                {ResultSetType.ForwardOnly, ScrollableCursorPolicy.TYPE_FORWARD_ONLY},
                {ResultSetType.Reverse, ScrollableCursorPolicy.FETCH_REVERSE},
                {ResultSetType.ScrollInsensitive, ScrollableCursorPolicy.TYPE_SCROLL_INSENSITIVE},
                {ResultSetType.ScrollSensitive, ScrollableCursorPolicy.TYPE_SCROLL_SENSITIVE},
                {ResultSetType.Unknown, ScrollableCursorPolicy.FETCH_UNKNOWN}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {  
            int value = (Integer)valueToApply;
            if (query.isReadAllQuery()) {
                if (!((ReadAllQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                    ((ReadAllQuery) query).useScrollableCursor();                    
                }
                ((ScrollableCursorPolicy)((ReadAllQuery) query).getContainerPolicy()).setResultSetType(value);
            } else if (query.isDataReadQuery()) {
                if (!((DataReadQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                    ((DataReadQuery) query).useScrollableCursor();
                }
                ((ScrollableCursorPolicy)((DataReadQuery) query).getContainerPolicy()).setResultSetType(value);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class ResultSetConcurrencyHint extends Hint {
        ResultSetConcurrencyHint() {
            super(QueryHints.RESULT_SET_CONCURRENCY, ResultSetConcurrency.DEFAULT);
            valueArray = new Object[][] {
                {ResultSetConcurrency.ReadOnly, ScrollableCursorPolicy.CONCUR_READ_ONLY},
                {ResultSetConcurrency.Updatable, ScrollableCursorPolicy.CONCUR_UPDATABLE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {  
            int value = (Integer)valueToApply;
            if (query.isReadAllQuery()) {
                if (!((ReadAllQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                    ((ReadAllQuery) query).useScrollableCursor();                    
                }
                ((ScrollableCursorPolicy)((ReadAllQuery) query).getContainerPolicy()).setResultSetConcurrency(value);
            } else if (query.isDataReadQuery()) {
                if (!((DataReadQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                    ((DataReadQuery) query).useScrollableCursor();
                }
                ((ScrollableCursorPolicy)((DataReadQuery) query).getContainerPolicy()).setResultSetConcurrency(value);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class ExclusiveHint extends Hint {
        ExclusiveHint() {
            super(QueryHints.EXCLUSIVE_CONNECTION, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectBuildingQuery()) {
                ((ObjectBuildingQuery)query).setShouldUseExclusiveConnection(((Boolean)valueToApply).booleanValue());
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class InheritanceJoinHint extends Hint {
        InheritanceJoinHint() {
            super(QueryHints.INHERITANCE_OUTER_JOIN, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery)query).setShouldOuterJoinSubclasses(((Boolean)valueToApply).booleanValue());
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class FetchGroupDefaultHint extends Hint {
        FetchGroupDefaultHint() {
            super(QueryHints.FETCH_GROUP_DEFAULT, HintValues.TRUE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery)query).setShouldUseDefaultFetchGroup(((Boolean)valueToApply).booleanValue());
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class FetchGroupNameHint extends Hint {
        FetchGroupNameHint() {
            super(QueryHints.FETCH_GROUP_NAME, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery)query).setFetchGroupName((String)valueToApply);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class FetchGroupHint extends Hint {
        FetchGroupHint() {
            super(QueryHints.FETCH_GROUP, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery)query).setFetchGroup((FetchGroup)valueToApply);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class FetchGroupAttributeHint extends Hint {
        FetchGroupAttributeHint() {
            super(QueryHints.FETCH_GROUP_ATTRIBUTE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                FetchGroup fetchGroup = ((ObjectLevelReadQuery)query).getFetchGroup();
                if (fetchGroup == null) {
                    fetchGroup = new FetchGroup();
                    ((ObjectLevelReadQuery)query).setFetchGroup(fetchGroup);
                }
                fetchGroup.addAttribute((String)valueToApply);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    /**
     * Define the query cache hint.
     * Only reset the query cache if unset (as other query cache properties may be set first).
     */
    protected static class QueryCacheHint extends Hint {
        QueryCacheHint() {
            super(QueryHints.QUERY_RESULTS_CACHE, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                if (((Boolean)valueToApply).booleanValue()) {
                    if (((ReadQuery)query).getQueryResultsCachePolicy() == null) {
                        ((ReadQuery)query).cacheQueryResults();
                    }
                }
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    /**
     * Define the query cache size hint.
     * Only reset the query cache if unset (as other query cache properties may be set first).
     */
    protected static class QueryCacheSizeHint extends Hint {
        QueryCacheSizeHint() {
            super(QueryHints.QUERY_RESULTS_CACHE_SIZE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                ReadQuery readQuery = (ReadQuery)query;
                if (readQuery.getQueryResultsCachePolicy() == null) {
                    readQuery.cacheQueryResults();
                }
                try {
                    readQuery.getQueryResultsCachePolicy().setMaximumCachedResults(Integer.parseInt((String)valueToApply));
                } catch (NumberFormatException exception) {
                    throw QueryException.queryHintContainedInvalidIntegerValue(QueryHints.QUERY_RESULTS_CACHE_SIZE, valueToApply, exception);
                }
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    /**
     * Define the query cache expiry hint.
     * Only reset the query cache if unset (as other query cache properties may be set first).
     */
    protected static class QueryCacheExpiryHint extends Hint {
        QueryCacheExpiryHint() {
            super(QueryHints.QUERY_RESULTS_CACHE_EXPIRY, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                ReadQuery readQuery = (ReadQuery)query;
                if (readQuery.getQueryResultsCachePolicy() == null) {
                    readQuery.cacheQueryResults();
                }
                try {
                    readQuery.getQueryResultsCachePolicy().setCacheInvalidationPolicy(
                            new TimeToLiveCacheInvalidationPolicy(Integer.parseInt((String)valueToApply)));
                } catch (NumberFormatException exception) {
                    throw QueryException.queryHintContainedInvalidIntegerValue(QueryHints.QUERY_RESULTS_CACHE_EXPIRY, valueToApply, exception);
                }
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    /**
     * Define the query cache expiry time of day hint.
     * Only reset the query cache if unset (as other query cache properties may be set first).
     */
    protected static class QueryCacheExpiryTimeOfDayHint extends Hint {
        QueryCacheExpiryTimeOfDayHint() {
            super(QueryHints.QUERY_RESULTS_CACHE_EXPIRY_TIME_OF_DAY, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                ReadQuery readQuery = (ReadQuery)query;
                if (readQuery.getQueryResultsCachePolicy() == null) {
                    readQuery.cacheQueryResults();
                }
                try {
                    Time time = Helper.timeFromString((String)valueToApply);
                    Calendar calendar = Calendar.getInstance();
                    calendar.setTime(time);
                    readQuery.getQueryResultsCachePolicy().setCacheInvalidationPolicy(
                            new DailyCacheInvalidationPolicy(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND), 0));
                } catch (ConversionException exception) {
                    throw QueryException.queryHintContainedInvalidIntegerValue(QueryHints.QUERY_RESULTS_CACHE_EXPIRY_TIME_OF_DAY, valueToApply, exception);
                }
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class BatchHint extends Hint {
        BatchHint() {
            super(QueryHints.BATCH, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadAllQuery() && !query.isReportQuery()) {
                ReadAllQuery raq = (ReadAllQuery)query;
                StringTokenizer tokenizer = new StringTokenizer((String)valueToApply, ".");
                if (tokenizer.countTokens() < 2){
                    throw QueryException.queryHintDidNotContainEnoughTokens(query, QueryHints.BATCH, valueToApply);
                }
                // ignore the first token since we are assuming read all query
                // e.g. In e.phoneNumbers we will assume "e" refers to the base of the query
                String previousToken = tokenizer.nextToken();
                ClassDescriptor descriptor = raq.getDescriptor();
                Expression expression = raq.getExpressionBuilder();
                while (tokenizer.hasMoreTokens()){
                    String token = tokenizer.nextToken();
                    ForeignReferenceMapping frMapping = null;
                    DatabaseMapping mapping = descriptor.getObjectBuilder().getMappingForAttributeName(token);
                    if (mapping == null){
                        throw QueryException.queryHintNavigatedNonExistantRelationship(query, QueryHints.BATCH, valueToApply, previousToken + "." + token);
                    } else if (!mapping.isForeignReferenceMapping()){
                        throw QueryException.queryHintNavigatedIllegalRelationship(query, QueryHints.BATCH, valueToApply, previousToken + "." + token);
                    } else {
                        frMapping = (ForeignReferenceMapping)mapping;
                    }
                    descriptor = frMapping.getReferenceDescriptor();
                    if (frMapping.isCollectionMapping()){
                        expression = expression.anyOf(token);
                    } else {
                        expression = expression.get(token);
                    }
                    previousToken = token;
                }
                raq.addBatchReadAttribute(expression);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class FetchHint extends Hint {
        FetchHint() {
            super(QueryHints.FETCH, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery() && !query.isReportQuery()) {
                ObjectLevelReadQuery olrq = (ObjectLevelReadQuery)query;
                StringTokenizer tokenizer = new StringTokenizer((String)valueToApply, ".");
                if (tokenizer.countTokens() < 2){
                    throw QueryException.queryHintDidNotContainEnoughTokens(query, QueryHints.FETCH, valueToApply);
                }
                // ignore the first token since we are assuming read all query
                // e.g. In e.phoneNumbers we will assume "e" refers to the base of the query
                String previousToken = tokenizer.nextToken();
                ClassDescriptor descriptor = olrq.getDescriptor();
                Expression expression = olrq.getExpressionBuilder();
                while (tokenizer.hasMoreTokens()){
                    String token = tokenizer.nextToken();
                    ForeignReferenceMapping frMapping = null;
                    DatabaseMapping mapping = descriptor.getObjectBuilder().getMappingForAttributeName(token);
                    if (mapping == null){
                        throw QueryException.queryHintNavigatedNonExistantRelationship(query, QueryHints.FETCH, valueToApply, previousToken + "." + token);
                    } else if (!mapping.isForeignReferenceMapping()){
                        throw QueryException.queryHintNavigatedIllegalRelationship(query, QueryHints.FETCH, valueToApply, previousToken + "." + token);
                    } else {
                        frMapping = (ForeignReferenceMapping)mapping;
                    }
                    descriptor = frMapping.getReferenceDescriptor();
                    if (frMapping.isCollectionMapping()){
                        expression = expression.anyOf(token);
                    } else {
                        expression = expression.get(token);
                    }
                    previousToken = token;
                }
                olrq.addJoinedAttribute(expression);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class LeftFetchHint extends Hint {
        LeftFetchHint() {
            super(QueryHints.LEFT_FETCH, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery() && !query.isReportQuery()) {
                ObjectLevelReadQuery olrq = (ObjectLevelReadQuery)query;
                StringTokenizer tokenizer = new StringTokenizer((String)valueToApply, ".");
                if (tokenizer.countTokens() < 2){
                    throw QueryException.queryHintDidNotContainEnoughTokens(query, QueryHints.LEFT_FETCH, valueToApply);
                }
                // ignore the first token since we are assuming read all query
                // e.g. In e.phoneNumbers we will assume "e" refers to the base of the query
                String previousToken = tokenizer.nextToken();
                ClassDescriptor descriptor = olrq.getDescriptor();
                Expression expression = olrq.getExpressionBuilder();
                while (tokenizer.hasMoreTokens()){
                    String token = tokenizer.nextToken();
                    ForeignReferenceMapping frMapping = null;
                    DatabaseMapping mapping = descriptor.getObjectBuilder().getMappingForAttributeName(token);
                    if (mapping == null){
                        throw QueryException.queryHintNavigatedNonExistantRelationship(query, QueryHints.LEFT_FETCH, valueToApply, previousToken + "." + token);
                    } else if (!mapping.isForeignReferenceMapping()){
                        throw QueryException.queryHintNavigatedIllegalRelationship(query, QueryHints.LEFT_FETCH, valueToApply, previousToken + "." + token);
                    } else {
                        frMapping = (ForeignReferenceMapping)mapping;
                    }
                    descriptor = frMapping.getReferenceDescriptor();
                    if (frMapping.isCollectionMapping()){
                        expression = expression.anyOfAllowingNone(token);
                    } else {
                        expression = expression.getAllowingNull(token);
                    }
                    previousToken = token;
                }
                olrq.addJoinedAttribute(expression);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    protected static class ReadOnlyHint extends Hint {
        ReadOnlyHint() {
            super(QueryHints.READ_ONLY, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery)query).setIsReadOnly(((Boolean)valueToApply).booleanValue());
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }

    protected static class NativeConnectionHint extends Hint {
        NativeConnectionHint() {
            super(QueryHints.NATIVE_CONNECTION, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setIsNativeConnectionRequired(((Boolean)valueToApply).booleanValue());
            return query;
        }
    }
    
    protected static class CursorHint extends Hint {
        CursorHint() {
            super(QueryHints.CURSOR, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (!((Boolean)valueToApply).booleanValue()) {
                if (query.isReadAllQuery()) {
                    if (((ReadAllQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                        ((ReadAllQuery) query).setContainerPolicy(ContainerPolicy.buildDefaultPolicy());
                    }
                } else if (query.isDataReadQuery()) {
                    if (((DataReadQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                        ((DataReadQuery) query).setContainerPolicy(ContainerPolicy.buildDefaultPolicy());
                    }
                }
            } else {
                if (query.isReadAllQuery()) {
                    if (!((ReadAllQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                        ((ReadAllQuery) query).useCursoredStream();
                    }
                } else if (query.isDataReadQuery()) {
                    if (!((DataReadQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                        ((DataReadQuery) query).useCursoredStream();
                    }
                } else {
                    throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
                }
            }
            
            return query;
        }
    }

    protected static class CursorInitialSizeHint extends Hint {
        CursorInitialSizeHint() {
            super(QueryHints.CURSOR_INITIAL_SIZE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadAllQuery()) {
                if (!((ReadAllQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                    ((ReadAllQuery) query).useCursoredStream();
                }
                ((CursoredStreamPolicy)((ReadAllQuery) query).getContainerPolicy()).setInitialReadSize(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.CURSOR_INITIAL_SIZE));
            } else if (query.isDataReadQuery()) {
                if (!((DataReadQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                    ((DataReadQuery) query).useCursoredStream();
                }
                ((CursoredStreamPolicy)((DataReadQuery) query).getContainerPolicy()).setInitialReadSize(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.CURSOR_INITIAL_SIZE));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }

    protected static class CursorPageSizeHint extends Hint {
        CursorPageSizeHint() {
            super(QueryHints.CURSOR_PAGE_SIZE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadAllQuery()) {
                if (!((ReadAllQuery) query).getContainerPolicy().isCursorPolicy()) {
                    ((ReadAllQuery) query).useCursoredStream();
                }
                ((CursorPolicy)((ReadAllQuery) query).getContainerPolicy()).setPageSize(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.CURSOR_PAGE_SIZE));
            } else if (query.isDataReadQuery()) {
                if (!((DataReadQuery) query).getContainerPolicy().isCursorPolicy()) {
                    ((DataReadQuery) query).useCursoredStream();
                }
                ((CursorPolicy)((DataReadQuery) query).getContainerPolicy()).setPageSize(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.CURSOR_PAGE_SIZE));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }

    protected static class CursorSizeHint extends Hint {
        CursorSizeHint() {
            super(QueryHints.CURSOR_SIZE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadAllQuery()) {
                if (!((ReadAllQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                    ((ReadAllQuery) query).useCursoredStream();
                }
                ((CursoredStreamPolicy)((ReadAllQuery) query).getContainerPolicy()).setSizeQuery(new ValueReadQuery((String)valueToApply));
            } else if (query.isDataReadQuery()) {
                if (!((DataReadQuery) query).getContainerPolicy().isCursoredStreamPolicy()) {
                    ((DataReadQuery) query).useCursoredStream();
                }
                ((CursoredStreamPolicy)((ReadAllQuery) query).getContainerPolicy()).setSizeQuery(new ValueReadQuery((String)valueToApply));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }

    protected static class ScrollableCursorHint extends Hint {
        ScrollableCursorHint() {
            super(QueryHints.SCROLLABLE_CURSOR, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (!((Boolean)valueToApply).booleanValue()) {
                if (query.isReadAllQuery()) {
                    if (((ReadAllQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                        ((ReadAllQuery) query).setContainerPolicy(ContainerPolicy.buildDefaultPolicy());
                    }
                } else if (query.isDataReadQuery()) {
                    if (((DataReadQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                        ((DataReadQuery) query).setContainerPolicy(ContainerPolicy.buildDefaultPolicy());
                    }
                }
            } else {
                if (query.isReadAllQuery()) {
                    if (!((ReadAllQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                        ((ReadAllQuery) query).useScrollableCursor();
                    }
                } else if (query.isDataReadQuery()) {
                    if (!((DataReadQuery) query).getContainerPolicy().isScrollableCursorPolicy()) {
                        ((DataReadQuery) query).useScrollableCursor();
                    }
                } else {
                    throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
                }
            }
            
            return query;
        }
    }
    
    protected static class MaintainCacheHint extends Hint {
        MaintainCacheHint() {
            super(QueryHints.MAINTAIN_CACHE, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setShouldMaintainCache(((Boolean)valueToApply).booleanValue());
            return query;
        }
    }

    protected static class PrepareHint extends Hint {
        PrepareHint() {
            super(QueryHints.PREPARE, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setShouldPrepare(((Boolean)valueToApply).booleanValue());
            return query;
        }
    }

    protected static class CacheStatementHint extends Hint {
        CacheStatementHint() {
            super(QueryHints.CACHE_STATMENT, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setShouldCacheStatement(((Boolean)valueToApply).booleanValue());
            return query;
        }
    }

    protected static class FlushHint extends Hint {
        FlushHint() {
            super(QueryHints.FLUSH, HintValues.FALSE);
            valueArray = new Object[][] { 
                {HintValues.FALSE, Boolean.FALSE},
                {HintValues.TRUE, Boolean.TRUE}
            };
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setFlushOnExecute((Boolean)valueToApply);
            return query;
        }
    }

    protected static class HintHint extends Hint {
        HintHint() {
            super(QueryHints.HINT, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setHintString((String)valueToApply);
            return query;
        }
    }
    
    protected static class JDBCTimeoutHint extends Hint {
        JDBCTimeoutHint() {
            super(QueryHints.JDBC_TIMEOUT, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            query.setQueryTimeout(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.JDBC_TIMEOUT));
            return query;
        }
    }
        
    protected static class JDBCFetchSizeHint extends Hint {
        JDBCFetchSizeHint() {
            super(QueryHints.JDBC_FETCH_SIZE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                ((ReadQuery) query).setFetchSize(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.JDBC_FETCH_SIZE));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }
        
    protected static class AsOfHint extends Hint {
        AsOfHint() {
            super(QueryHints.AS_OF, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery) query).setAsOfClause(new AsOfClause(Helper.timeFromString((String)valueToApply)));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }
        
    protected static class AsOfSCNHint extends Hint {
        AsOfSCNHint() {
            super(QueryHints.AS_OF_SCN, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isObjectLevelReadQuery()) {
                ((ObjectLevelReadQuery) query).setAsOfClause(new AsOfSCNClause(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.AS_OF_SCN)));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            
            return query;
        }
    }
    
    protected static class JDBCMaxRowsHint extends Hint {
        JDBCMaxRowsHint() {
            super(QueryHints.JDBC_MAX_ROWS, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                ((ReadQuery) query).setMaxRows(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.JDBC_MAX_ROWS));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class JDBCFirstResultHint extends Hint {
        JDBCFirstResultHint() {
            super(QueryHints.JDBC_FIRST_RESULT, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadQuery()) {
                ((ReadQuery) query).setFirstResult(QueryHintsHandler.parseIntegerHint(valueToApply, QueryHints.JDBC_FIRST_RESULT));
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class ResultCollectionTypeHint extends Hint {
        ResultCollectionTypeHint() {
            super(QueryHints.RESULT_COLLECTION_TYPE, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            if (query.isReadAllQuery()) {
                Class collectionClass = null;
                if (valueToApply instanceof String) {
                    collectionClass = loadClass((String)valueToApply, query, loader);
                } else {
                    collectionClass = (Class)valueToApply;
                }
                ((ReadAllQuery)query).useCollectionClass(collectionClass);
            } else {
                throw new IllegalArgumentException(ExceptionLocalization.buildMessage("ejb30-wrong-type-for-query-hint",new Object[]{getQueryId(query), name, getPrintValue(valueToApply)}));
            }
            return query;
        }
    }
    
    protected static class RedirectorHint extends Hint {
        RedirectorHint() {
            super(QueryHints.QUERY_REDIRECTOR, "");
        }
    
        DatabaseQuery applyToDatabaseQuery(Object valueToApply, DatabaseQuery query, ClassLoader loader) {
            // Can be an instance, class, or class name.
            try {
                Object redirector = valueToApply;
                if (valueToApply instanceof Class) {
                    redirector = newInstance((Class)valueToApply, query, QueryHints.QUERY_REDIRECTOR);
                } else if (valueToApply instanceof String) {
                    Class redirectorClass = loadClass((String)valueToApply, query, loader);
                    redirector = newInstance(redirectorClass, query, QueryHints.QUERY_REDIRECTOR);
                }
                query.setRedirector((QueryRedirector)redirector);
            } catch (ClassCastException exception){
                throw QueryException.unableToSetRedirectorOnQueryFromHint(query,QueryHints.QUERY_REDIRECTOR, valueToApply.getClass().getName(), exception);
            }
            return query;
        }
    }
}
