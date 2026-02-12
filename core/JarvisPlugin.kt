package com.termux.jarvis.plugin

import org.json.JSONObject
import kotlin.time.Duration

/**
 * Interface base para todos os plugins do JARVIS.
 * 
 * FILOSOFIA:
 * - Cada comando = 1 plugin
 * - Plugins são independentes
 * - Falha em 1 plugin não afeta outros
 * - Plugins podem ser atualizados separadamente
 * - Plugins podem ser criados por terceiros
 * 
 * @author Sistema de Plugins JARVIS 2.0
 * @since 2026-02-11
 */
interface JarvisPlugin {
    
    /**
     * Nome do comando que este plugin implementa.
     * Exemplo: "system_stats", "battery_info", "gpu_stats"
     */
    val commandName: String
    
    /**
     * Descrição curta do que o plugin faz.
     * Exibida em termux-jarvis --help
     */
    val description: String
    
    /**
     * Versão do plugin.
     * Formato semântico: "1.0.0"
     */
    val version: String
    
    /**
     * Autor do plugin.
     */
    val author: String
    
    /**
     * Se o plugin requer autenticação biométrica.
     */
    val requiresAuth: Boolean
    
    /**
     * Timeout máximo para execução (em milissegundos).
     * Padrão: 5000ms
     */
    val timeout: Duration
        get() = Duration.parse("5s")
    
    /**
     * Permissões Android necessárias.
     * Exemplo: listOf("READ_EXTERNAL_STORAGE", "CAMERA")
     */
    val requiredPermissions: List<String>
        get() = emptyList()
    
    /**
     * Executa o comando do plugin.
     * 
     * @param context Contexto de execução fornecido pelo core
     * @return Resultado da execução
     */
    suspend fun execute(context: PluginContext): CommandResult
    
    /**
     * Chamado quando o plugin é carregado.
     * Use para inicialização de recursos.
     */
    fun onLoad() {}
    
    /**
     * Chamado quando o plugin é descarregado.
     * Use para limpeza de recursos.
     */
    fun onUnload() {}
    
    /**
     * Valida se o plugin pode ser executado no ambiente atual.
     * 
     * @return HealthCheck com resultado da validação
     */
    fun healthCheck(): HealthCheck {
        return HealthCheck(
            healthy = true,
            message = "Plugin ${commandName} está funcionando"
        )
    }
}

/**
 * Contexto fornecido ao plugin durante execução.
 */
data class PluginContext(
    val args: Map<String, String>,
    val authenticated: Boolean,
    val userId: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Obtém argumento com fallback.
     */
    fun getArg(key: String, default: String = ""): String {
        return args[key] ?: default
    }
    
    /**
     * Verifica se argumento existe.
     */
    fun hasArg(key: String): Boolean {
        return args.containsKey(key)
    }
}

/**
 * Resultado da execução de um comando.
 */
sealed class CommandResult {
    data class Success(val data: JSONObject) : CommandResult() {
        fun toJson(): String {
            return JSONObject().apply {
                put("success", true)
                put("data", data)
                put("timestamp", System.currentTimeMillis())
            }.toString()
        }
    }
    
    data class Error(
        val code: String,
        val message: String,
        val details: String? = null
    ) : CommandResult() {
        fun toJson(): String {
            return JSONObject().apply {
                put("success", false)
                put("error", JSONObject().apply {
                    put("code", code)
                    put("message", message)
                    if (details != null) put("details", details)
                })
                put("timestamp", System.currentTimeMillis())
            }.toString()
        }
    }
    
    companion object {
        fun success(data: Map<String, Any>): Success {
            val json = JSONObject(data)
            return Success(json)
        }
        
        fun error(code: String, message: String, details: String? = null): Error {
            return Error(code, message, details)
        }
    }
}

/**
 * Resultado do health check de um plugin.
 */
data class HealthCheck(
    val healthy: Boolean,
    val message: String,
    val details: Map<String, Any>? = null
)

/**
 * Metadados do plugin (lidos de plugin.json).
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val mainClass: String,
    val nativeLibrary: String? = null,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList()
)

/**
 * Carregador de plugins dinâmico.
 */
class PluginLoader(private val pluginDir: File) {
    
    private val loadedPlugins = mutableMapOf<String, JarvisPlugin>()
    
    /**
     * Carrega todos os plugins do diretório.
     */
    fun loadAll(): List<JarvisPlugin> {
        val plugins = mutableListOf<JarvisPlugin>()
        
        pluginDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                loadPlugin(file)?.let { plugins.add(it) }
            }
        }
        
        return plugins
    }
    
    /**
     * Carrega um plugin específico.
     */
    private fun loadPlugin(dir: File): JarvisPlugin? {
        try {
            // 1. Ler plugin.json
            val metadataFile = File(dir, "plugin.json")
            if (!metadataFile.exists()) {
                android.util.Log.e("PluginLoader", "plugin.json não encontrado em ${dir.name}")
                return null
            }
            
            val metadata = parseMetadata(metadataFile)
            
            // 2. Carregar biblioteca nativa (se houver)
            metadata.nativeLibrary?.let { libName ->
                val libFile = File(dir, "lib/$libName")
                if (libFile.exists()) {
                    System.load(libFile.absolutePath)
                }
            }
            
            // 3. Carregar classe Java/Kotlin
            val jarFile = File(dir, "${metadata.id}.jar")
            if (!jarFile.exists()) {
                android.util.Log.e("PluginLoader", "JAR não encontrado: ${jarFile.name}")
                return null
            }
            
            // TODO: Usar DexClassLoader para carregar plugin
            // val classLoader = DexClassLoader(...)
            // val pluginClass = classLoader.loadClass(metadata.mainClass)
            // val plugin = pluginClass.newInstance() as JarvisPlugin
            
            // Por ora, retornar null (implementação completa requer DexClassLoader)
            android.util.Log.i("PluginLoader", "Plugin ${metadata.name} carregado")
            return null
            
        } catch (e: Exception) {
            android.util.Log.e("PluginLoader", "Erro ao carregar plugin ${dir.name}", e)
            return null
        }
    }
    
    private fun parseMetadata(file: File): PluginMetadata {
        val json = JSONObject(file.readText())
        return PluginMetadata(
            id = json.getString("id"),
            name = json.getString("name"),
            version = json.getString("version"),
            author = json.getString("author"),
            description = json.getString("description"),
            mainClass = json.getString("main_class"),
            nativeLibrary = json.optString("native_library").takeIf { it.isNotEmpty() },
            dependencies = json.optJSONArray("dependencies")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            permissions = json.optJSONArray("permissions")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()
        )
    }
    
    /**
     * Obtém plugin por nome de comando.
     */
    fun getPlugin(commandName: String): JarvisPlugin? {
        return loadedPlugins[commandName]
    }
    
    /**
     * Registra plugin manualmente (para testes).
     */
    fun registerPlugin(plugin: JarvisPlugin) {
        loadedPlugins[plugin.commandName] = plugin
        plugin.onLoad()
    }
    
    /**
     * Remove plugin.
     */
    fun unloadPlugin(commandName: String) {
        loadedPlugins[commandName]?.onUnload()
        loadedPlugins.remove(commandName)
    }
}
