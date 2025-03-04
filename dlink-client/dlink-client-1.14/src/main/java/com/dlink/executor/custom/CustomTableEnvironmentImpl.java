package com.dlink.executor.custom;

import com.dlink.result.SqlExplainResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.JSONGenerator;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.ExplainDetail;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.FunctionCatalog;
import org.apache.flink.table.catalog.GenericInMemoryCatalog;
import org.apache.flink.table.delegation.Executor;
import org.apache.flink.table.delegation.ExecutorFactory;
import org.apache.flink.table.delegation.Planner;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.PlannerFactoryUtil;
import org.apache.flink.table.functions.AggregateFunction;
import org.apache.flink.table.functions.TableAggregateFunction;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.functions.UserDefinedFunctionHelper;
import org.apache.flink.table.module.ModuleManager;
import org.apache.flink.table.operations.ExplainOperation;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.QueryOperation;
import org.apache.flink.table.planner.delegation.DefaultExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 定制TableEnvironmentImpl
 *
 * @author wenmo
 * @since 2021/10/22 10:02
 **/
public class CustomTableEnvironmentImpl extends TableEnvironmentImpl {

    protected CustomTableEnvironmentImpl(CatalogManager catalogManager, ModuleManager moduleManager, TableConfig tableConfig, Executor executor, FunctionCatalog functionCatalog, Planner planner, boolean isStreamingMode, ClassLoader userClassLoader) {
        super(catalogManager, moduleManager, tableConfig, executor, functionCatalog, planner, isStreamingMode, userClassLoader);
    }

    public CustomTableEnvironmentImpl(
            CatalogManager catalogManager,
            ModuleManager moduleManager,
            FunctionCatalog functionCatalog,
            TableConfig tableConfig,
            StreamExecutionEnvironment executionEnvironment,
            Planner planner,
            Executor executor,
            boolean isStreamingMode,
            ClassLoader userClassLoader) {
        super(
                catalogManager,
                moduleManager,
                tableConfig,
                executor,
                functionCatalog,
                planner,
                isStreamingMode,
                userClassLoader);
    }

    public static CustomTableEnvironmentImpl create(StreamExecutionEnvironment executionEnvironment){
        return create(executionEnvironment,EnvironmentSettings.newInstance().build(),TableConfig.getDefault());
    }

    public static CustomTableEnvironmentImpl create(
            StreamExecutionEnvironment executionEnvironment,
            EnvironmentSettings settings,
            TableConfig tableConfig) {

        // temporary solution until FLINK-15635 is fixed
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        final ModuleManager moduleManager = new ModuleManager();

        final CatalogManager catalogManager =
                CatalogManager.newBuilder()
                        .classLoader(classLoader)
                        .config(tableConfig.getConfiguration())
                        .defaultCatalog(
                                settings.getBuiltInCatalogName(),
                                new GenericInMemoryCatalog(
                                        settings.getBuiltInCatalogName(),
                                        settings.getBuiltInDatabaseName()))
                        .executionConfig(executionEnvironment.getConfig())
                        .build();

        final FunctionCatalog functionCatalog =
                new FunctionCatalog(tableConfig, catalogManager, moduleManager);

        final Executor executor =
                lookupExecutor(classLoader, settings.getExecutor(), executionEnvironment);

        final Planner planner =
                PlannerFactoryUtil.createPlanner(
                        settings.getPlanner(),
                        executor,
                        tableConfig,
                        catalogManager,
                        functionCatalog);

        return new CustomTableEnvironmentImpl(
                catalogManager,
                moduleManager,
                functionCatalog,
                tableConfig,
                executionEnvironment,
                planner,
                executor,
                settings.isStreamingMode(),
                classLoader);
    }

    private static Executor lookupExecutor(
            ClassLoader classLoader,
            String executorIdentifier,
            StreamExecutionEnvironment executionEnvironment) {
        try {
            final ExecutorFactory executorFactory =
                    FactoryUtil.discoverFactory(
                            classLoader, ExecutorFactory.class, executorIdentifier);
            final Method createMethod =
                    executorFactory
                            .getClass()
                            .getMethod("create", StreamExecutionEnvironment.class);

            return (Executor) createMethod.invoke(executorFactory, executionEnvironment);
        } catch (Exception e) {
            throw new TableException(
                    "Could not instantiate the executor. Make sure a planner module is on the classpath",
                    e);
        }
    }

    public ObjectNode getStreamGraph(String statement) {
        List<Operation> operations = super.getParser().parse(statement);
        if (operations.size() != 1) {
            throw new TableException("Unsupported SQL query! explainSql() only accepts a single SQL query.");
        } else {
            List<ModifyOperation> modifyOperations = new ArrayList<>();
            for (int i = 0; i < operations.size(); i++) {
                if(operations.get(i) instanceof ModifyOperation){
                    modifyOperations.add((ModifyOperation)operations.get(i));
                }
            }
            List<Transformation<?>> trans = super.planner.translate(modifyOperations);
            if(execEnv instanceof DefaultExecutor){
                StreamGraph streamGraph = ((DefaultExecutor) execEnv).getExecutionEnvironment().generateStreamGraph(trans);
                JSONGenerator jsonGenerator = new JSONGenerator(streamGraph);
                String json = jsonGenerator.getJSON();
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode objectNode =mapper.createObjectNode();
                try {
                    objectNode = (ObjectNode) mapper.readTree(json);
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }finally {
                    return objectNode;
                }
            }else{
                throw new TableException("Unsupported SQL query! explainSql() need a single SQL to query.");
            }
        }
    }

    public JobGraph getJobGraphFromInserts(List<String> statements) {
        List<ModifyOperation> modifyOperations = new ArrayList();
        for(String statement : statements){
            List<Operation> operations = getParser().parse(statement);
            if (operations.size() != 1) {
                throw new TableException("Only single statement is supported.");
            } else {
                Operation operation = operations.get(0);
                if (operation instanceof ModifyOperation) {
                    modifyOperations.add((ModifyOperation)operation);
                } else {
                    throw new TableException("Only insert statement is supported now.");
                }
            }
        }
        List<Transformation<?>> trans = getPlanner().translate(modifyOperations);
        if(execEnv instanceof DefaultExecutor){
            StreamGraph streamGraph = ((DefaultExecutor) execEnv).getExecutionEnvironment().generateStreamGraph(trans);
            return streamGraph.getJobGraph();
        }else{
            throw new TableException("Unsupported SQL query! ExecEnv need a ExecutorBase.");
        }
    }

    public SqlExplainResult explainSqlRecord(String statement, ExplainDetail... extraDetails) {
        SqlExplainResult record = new SqlExplainResult();
        List<Operation> operations = getParser().parse(statement);
        record.setParseTrue(true);
        if (operations.size() != 1) {
            throw new TableException(
                    "Unsupported SQL query! explainSql() only accepts a single SQL query.");
        }
        List<Operation> operationlist = new ArrayList<>(operations);
        for (int i = 0; i < operationlist.size(); i++) {
            Operation operation = operationlist.get(i);
            if (operation instanceof ModifyOperation) {
                record.setType("Modify DML");
            } else if (operation instanceof ExplainOperation) {
                record.setType("Explain DML");
            } else if (operation instanceof QueryOperation) {
                record.setType("Query DML");
            } else {
                record.setExplain(operation.asSummaryString());
                operationlist.remove(i);
                record.setType("DDL");
                i=i-1;
            }
        }
        record.setExplainTrue(true);
        if(operationlist.size()==0){
            //record.setExplain("DDL语句不进行解释。");
            return record;
        }
        record.setExplain(planner.explain(operationlist, extraDetails));
        return record;
    }

    public <T> void registerFunction(String name, TableFunction<T> tableFunction) {
        TypeInformation<T> typeInfo = UserDefinedFunctionHelper.getReturnTypeOfTableFunction(tableFunction);
        this.functionCatalog.registerTempSystemTableFunction(name, tableFunction, typeInfo);
    }

    public <T, ACC> void registerFunction(String name, AggregateFunction<T, ACC> aggregateFunction) {
        TypeInformation<T> typeInfo = UserDefinedFunctionHelper.getReturnTypeOfAggregateFunction(aggregateFunction);
        TypeInformation<ACC> accTypeInfo = UserDefinedFunctionHelper.getAccumulatorTypeOfAggregateFunction(aggregateFunction);
        this.functionCatalog.registerTempSystemAggregateFunction(name, aggregateFunction, typeInfo, accTypeInfo);
    }

    public <T, ACC> void registerFunction(String name, TableAggregateFunction<T, ACC> tableAggregateFunction) {
        TypeInformation<T> typeInfo = UserDefinedFunctionHelper.getReturnTypeOfAggregateFunction(tableAggregateFunction);
        TypeInformation<ACC> accTypeInfo = UserDefinedFunctionHelper.getAccumulatorTypeOfAggregateFunction(tableAggregateFunction);
        this.functionCatalog.registerTempSystemAggregateFunction(name, tableAggregateFunction, typeInfo, accTypeInfo);
    }
}
