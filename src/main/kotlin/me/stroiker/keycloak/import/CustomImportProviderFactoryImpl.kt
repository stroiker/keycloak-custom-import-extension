package me.stroiker.keycloak.import

import org.keycloak.Config
import org.keycloak.exportimport.ExportImportConfig
import org.keycloak.exportimport.ImportProvider
import org.keycloak.exportimport.ImportProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory

class CustomImportProviderFactoryImpl : ImportProviderFactory {

    private lateinit var config: Config.Scope

    override fun create(session: KeycloakSession): ImportProvider {
        val dir = System.getProperty(ExportImportConfig.DIR)
        return CustomImportProviderImpl(requireNotNull(dir) { "dir is required" })

    }

    override fun init(config: Config.Scope) {
        this.config = config
    }

    override fun postInit(factory: KeycloakSessionFactory) {}

    override fun close() {}

    override fun getId(): String = PROVIDER_ID

    private companion object {
        private const val PROVIDER_ID = "custom"
    }
}