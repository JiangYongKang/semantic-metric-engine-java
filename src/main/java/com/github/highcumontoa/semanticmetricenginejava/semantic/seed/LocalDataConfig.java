package com.github.highcumontoa.semanticmetricenginejava.semantic.seed;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.CsvDataLoader;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.DataSet;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.TableData;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.ModelValidator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryService;
import com.github.highcumontoa.semanticmetricenginejava.semantic.version.ModelRegistry;

/**
 * 本地装配：从 classpath:data/sales.csv 加载数据集，注册 v1/v2 模型，
 * 暴露 {@link QueryService} 供 HTTP 与本地调用复用。
 */
@Configuration
public class LocalDataConfig {

    @Bean
    public ModelValidator modelValidator() {
        return new ModelValidator();
    }

    @Bean
    public ModelRegistry modelRegistry(ModelValidator validator) {
        ModelRegistry registry = new ModelRegistry(validator);
        SeedModels.all().forEach(registry::register);
        return registry;
    }

    @Bean
    public DataSet dataSet() {
        CsvDataLoader loader = new CsvDataLoader();
        TableData sales = loader.load(SalesTable.def());
        Map<String, TableData> tables = new LinkedHashMap<>();
        tables.put(sales.name(), sales);
        return new DataSet(tables);
    }

    @Bean
    public QueryService queryService(ModelRegistry registry, DataSet dataSet) {
        return new QueryService(registry, dataSet);
    }
}
