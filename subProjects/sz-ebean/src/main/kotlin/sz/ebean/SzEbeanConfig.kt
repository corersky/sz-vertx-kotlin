package sz.ebean

import com.google.common.reflect.ClassPath
import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ebean.EbeanServerFactory
import io.ebean.config.ServerConfig
import jodd.introspector.ClassIntrospector
import org.apache.commons.lang3.concurrent.BasicThreadFactory
import sz.ebean.SzEbeanConfig.hikariConfigKeys
import sz.scaffold.Application
import sz.scaffold.ext.getStringListOrEmpty
import sz.scaffold.tools.BizLogicException
import sz.scaffold.tools.logger.Logger
import java.util.*
import java.util.concurrent.*

//
// Created by kk on 17/8/20.
//
@Suppress("MemberVisibilityCanBePrivate", "ObjectPropertyName", "unused")
object SzEbeanConfig {

    private val ebeanConfig: Config = Application.config.getConfig("ebean")
    private val defaultDatasourceName: String
    private val _ebeanServerConfigs = mutableMapOf<String, ServerConfig>()
    private val workerPoolMap = mutableMapOf<String, ThreadPoolExecutor>()


    val ebeanServerConfigs: Map<String, ServerConfig>
        get() = _ebeanServerConfigs

    val hasDbConfiged: Boolean

    val hikariConfigKeys by lazy {
        val classDescriptor = ClassIntrospector.get().lookup(HikariConfig::class.java)
        return@lazy classDescriptor.allPropertyDescriptors.filter {
            it.writeMethodDescriptor != null && it.writeMethodDescriptor.isPublic
                && it.readMethodDescriptor != null
                && (it.readMethodDescriptor.rawReturnType.isPrimitive || it.readMethodDescriptor.rawReturnType == String::class.java)
        }.map {
            it.name
        }.toSet()
    }

    init {
        defaultDatasourceName = ebeanConfig.getString("defaultDatasource")
        val dataSources = ebeanConfig.getConfig("dataSources")
        hasDbConfiged = dataSources.root().size > 0
    }

    fun loadConfig() {
        val dataSources = ebeanConfig.getConfig("dataSources")
        val modelClassSet = ebeanModels()

        dataSources.root().keys.forEach {
            val dataSourceName = it
            val dataSourceConfig = dataSources.getConfig(it)
            val dataSourceProps = dataSourceConfig.toProperties()
            val hikariConfig = HikariConfig(dataSourceProps)
            val ds = HikariDataSource(hikariConfig)

            val threadFactory = BasicThreadFactory.Builder()
                .wrappedFactory(Executors.defaultThreadFactory())
                .namingPattern("ebean-worker-$dataSourceName-%d")
                .build()

            workerPoolMap[dataSourceName] = ThreadPoolExecutor(0,
                ds.maximumPoolSize,
                60,
                TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(1024),
                threadFactory)

            val ebeanServerCfg = ServerConfig()
            ebeanServerCfg.name = dataSourceName
            ebeanServerCfg.loadFromProperties()
            ebeanServerCfg.dataSource = ds

            ebeanServerCfg.isDefaultServer = ebeanServerCfg.name == defaultDatasourceName


            ebeanServerCfg.addModelClasses(modelClassSet)

            EbeanServerFactory.create(ebeanServerCfg)

            _ebeanServerConfigs[dataSourceName] = ebeanServerCfg
        }
    }

    private val _dataSourceByTagCache = mutableMapOf("" to arrayOf(""))

    fun dataSourceByTag(tag: String): Array<String> {
        return _dataSourceByTagCache.getOrPut(tag) {
            val dataSources = ebeanConfig.getConfig("dataSources")
            val dsNames = dataSources.root().keys.filter {
                val config = dataSources.getConfig(it)
                config.getStringListOrEmpty("tags").toSet().contains(tag)
            }.toSet().toTypedArray()

            if (dsNames.isEmpty()) {
                throw BizLogicException("没有匹配此tag: '$tag' 的dataSource, 请检查 application.conf 相关配置")
            }
            dsNames
        }
    }

    @Suppress("UnstableApiUsage")
    private fun ebeanModels(): Set<Class<*>> {
        val cfgVal = ebeanConfig.getValue("ebeanModels")
        val models = if (cfgVal.valueType() == ConfigValueType.STRING) {
            cfgVal.unwrapped().toString().split(",").map { it.trim() }.toSet()
        } else {
            ebeanConfig.getStringList("ebeanModels").map { it.trim() }.toSet()
        }
        val modelClassSet = mutableSetOf<Class<*>>()
        models.forEach {
            if (it.endsWith(".*")) {
                val packagePath = it.dropLast(2)
                ClassPath.from(Application.classLoader).getTopLevelClassesRecursive(packagePath).forEach { classInfo ->
                    modelClassSet.add(classInfo.load())
                }
            } else {
                try {
                    val clazz = Application.classLoader.loadClass(it)
                    modelClassSet.add(clazz)
                } catch (ex: Exception) {
                    Logger.error("Load class: [$it] failed. For reason: ${ex.message}")
                }

            }
        }
        return modelClassSet
    }

    fun jdbcUrl(dataSource: String = "default"): String {
        val dsConfig = ebeanConfig.getConfig("dataSources.$dataSource")
        return dsConfig.getString("jdbcUrl")
    }

    fun isMySql(dataSource: String = "default"): Boolean {
        return jdbcUrl(dataSource).startsWith("jdbc:mysql:")
    }

    fun isH2(dataSource: String = "default"): Boolean {
        return jdbcUrl(dataSource).startsWith("jdbc:h2:")
    }

    fun isHsqldb(dataSource: String = "default"): Boolean {
        return jdbcUrl(dataSource).startsWith("jdbc:hsqldb:")
    }

    fun workerOf(dataSource: String): ExecutorService {
        return workerPoolMap[dataSource] ?: throw RuntimeException("Invalid data source name: $dataSource")
    }
}

private fun Config.toProperties(): Properties {
    val props = Properties()
    this.root().forEach { key, cfgValue ->
        if (key in hikariConfigKeys) {
            val value = cfgValue.unwrapped()
            if (value != null) {
                props.setProperty(key, cfgValue.unwrapped().toString())
            }
        }
    }
    return props
}

private fun ServerConfig.addModelClasses(modelClasses: Set<Class<*>>) {
    modelClasses.forEach { clazz ->
        this.addModelClass(clazz)
    }
}

private fun ServerConfig.addModelClass(clazz: Class<*>) {
    try {
//        Logger.debug("add class for ebean server: $clazz")
        this.addClass(clazz)
    } catch (ex: Exception) {
        Logger.error("ebean.dataSources.${this.name} Cannot register class [${clazz.name}] in Ebean server. For reason:${ex.message}")
    }
}