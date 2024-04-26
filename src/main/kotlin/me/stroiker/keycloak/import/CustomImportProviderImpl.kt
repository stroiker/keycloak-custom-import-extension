package me.stroiker.keycloak.import

import com.fasterxml.jackson.core.JsonToken
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.connections.jpa.JpaConnectionProvider
import org.keycloak.connections.jpa.util.JpaUtils
import org.keycloak.exportimport.ImportProvider
import org.keycloak.exportimport.Strategy
import org.keycloak.models.*
import org.keycloak.models.jpa.*
import org.keycloak.models.jpa.entities.*
import org.keycloak.models.utils.KeycloakModelUtils
import org.keycloak.models.utils.RepresentationToModel
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.keycloak.services.managers.RealmManager
import org.keycloak.util.JsonSerialization
import org.keycloak.utils.StreamsUtil
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors

class CustomImportProviderImpl(
    private val factory: KeycloakSessionFactory,
    private val strategy: Strategy,
    private val realmName: String?,
    private val withUsers: Boolean,
    dir: String
) : ImportProvider {

    private val rootDirectory: File

    init {
        LOGGER.info("Initialize ${this::class.simpleName} provider")
        rootDirectory = File(dir).also {
            if (!it.exists()) throw IllegalStateException("Directory '$dir' doesn't exist")
            LOGGER.info("Realm config directory '$dir'")
        }
    }

    override fun isMasterRealmExported(): Boolean = getRealmsToImport().contains(Config.getAdminRealm())

    override fun close() {}

    override fun importModel() {
        if (realmName != null) {
            importRealm(factory, realmName, strategy)
        } else {
            getRealmsToImport().forEach { realmName ->
                importRealm(factory, realmName, strategy)
            }
        }
    }

    private fun importRealm(factory: KeycloakSessionFactory, realmName: String, strategy: Strategy) {
        File(rootDirectory.toString() + File.separator + realmName + "-realm.json").also { realmFile ->
            JsonSerialization.readValue(FileInputStream(realmFile), RealmRepresentation::class.java).also { realmRep ->
                LOGGER.info("Starting to import realm '$realmName'")
                val realmImported = AtomicBoolean()

                KeycloakModelUtils.runJobInTransaction(factory) { session ->
                    kotlin.runCatching { realmImported.set(importRealmInternal(session, realmRep, strategy)) }
                        .onFailure { throw RuntimeException("Error during import realm $realmName: ${it.message}", it) }
                }

                if (realmImported.get()) {
                    // Import authorization and initialize service accounts last, as they require users already in DB
                    KeycloakModelUtils.runJobInTransaction(factory) { session ->
                        session.setAttribute("ALLOW_CREATE_POLICY", true) // enforce upload JS policies
                        kotlin.runCatching {
                            val realmManager = RealmManager(session)
                            realmManager.setupClientServiceAccountsAndAuthorizationOnImport(realmRep, false)
                        }.onFailure {
                            throw RuntimeException("Error during import realm '$realmName': ${it.message}", it)
                        }
                    }
                }
                LOGGER.info("Realm '$realmName' imported successfully")

                if(withUsers) {
                    importUsersInternal(realmName)
                }
            }
        }
    }

    private fun parseRealmUsers(file: File, realmName: String): List<UserRepresentation> {
        val userReps = mutableListOf<UserRepresentation>()
        FileInputStream(file).use { fis ->
            JsonSerialization.mapper.factory.createParser(fis).use { parser ->
                parser.nextToken()
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    if ("realm" == parser.text) {
                        parser.nextToken()
                        check(parser.text == realmName) { "Trying to import users into invalid realm. Realm name: $realmName, Expected realm name: ${parser.text}" }
                    } else if ("users" == parser.text) {
                        parser.nextToken()
                        if (parser.currentToken == JsonToken.START_ARRAY) {
                            parser.nextToken()
                        }
                        while (parser.currentToken == JsonToken.START_OBJECT) {
                            userReps.add(parser.readValueAs(UserRepresentation::class.java))
                            parser.nextToken()
                        }
                        if (parser.currentToken == JsonToken.END_ARRAY) {
                            parser.nextToken()
                        }
                    }
                }
            }
        }
        return userReps
    }

    private fun importUsersInternal(realmName: String) {
        // Import users which not exist
        rootDirectory.listFiles { _: File, name: String ->
            name.matches("$realmName-users-[0-9]+\\.json".toRegex())
        }?.forEach { userFile ->
            LOGGER.info("Starting to import users for realm '$realmName'")
            parseRealmUsers(userFile, realmName).also { userReps ->
                KeycloakModelUtils.runJobInTransaction(factory) { session ->
                    kotlin.runCatching {
                        val entityManager =
                            session.getProvider(JpaConnectionProvider::class.java).entityManager
                        session.realms()
                        val realm = session.realms().getRealmByName(realmName)
                        userReps.filter { userRep ->
                            entityManager.createNamedQuery("getRealmUserByUsername")
                                .setParameter("realmId", realm.id)
                                .setParameter("username", userRep.username)
                                .resultList
                                .isNullOrEmpty()
                        }.forEach { userRep ->
                            RepresentationToModel.createUser(session, realm, userRep)
                        }
                    }.onFailure {
                        throw RuntimeException(
                            "Error during import users for realm '$realmName': ${it.message}",
                            it
                        )
                    }
                }
            }
            LOGGER.info("Realm '$realmName' users imported successfully")
        }
    }

    private fun getRealmsToImport(): List<String> =
        rootDirectory.listFiles { _, name -> name.endsWith("-realm.json") }?.let { realmFiles ->
            val realmNames = mutableListOf<String>()
            for (file in realmFiles) {
                val fileName = file.name
                // Parse "foo" from "foo-realm.json"
                val realmName = fileName.substring(0, fileName.length - 11)

                // Ensure that master realm is imported first
                if (Config.getAdminRealm() == realmName) {
                    realmNames.add(0, realmName)
                } else {
                    realmNames.add(realmName)
                }
            }
            realmNames
        } ?: emptyList()

    private fun importRealmInternal(session: KeycloakSession, rep: RealmRepresentation, strategy: Strategy): Boolean {
        val entityManager = session.getProvider(JpaConnectionProvider::class.java).entityManager
        val realmManager = RealmManager(session)
        val realmName = rep.realm
        val model = session.realms()
        val realm = model.getRealmByName(realmName)
        if (realm != null) {
            if (strategy == Strategy.IGNORE_EXISTING) {
                LOGGER.info("Realm '$realmName' already exists. Import skipped")
                return false
            }
            val rolesBeforeUpdate = getRoles(realm.id, entityManager)
            val groupsBeforeUpdate = getGroups(realm.id, entityManager)

            LOGGER.info("Removing realm '$realmName'...")
            removeRealm(realm.id, entityManager, session)
            rep.users.takeUnless { it.isNullOrEmpty() }?.also { defaultUsers ->
                LOGGER.info("Removing realm '$realmName' default users...")
                defaultUsers.forEach { userRep ->
                    entityManager.createNamedQuery("getRealmUserByUsername", UserEntity::class.java)
                        .setParameter("realmId", realm.id)
                        .setParameter("username", userRep.username)
                        .resultList
                        .firstOrNull()
                        ?.also { user ->
                            entityManager.createNamedQuery("deleteUserRoleMappingsByUser")
                                .setParameter("user", user)
                                .executeUpdate()
                            entityManager.createNamedQuery("deleteUserGroupMembershipsByUser")
                                .setParameter("user", user)
                                .executeUpdate()
                            entityManager.remove(user)
                            entityManager.flush()
                        }
                }
            }
            LOGGER.info("Importing realm '$realmName'...")
            realmManager.importRealm(rep, true)

            getRoles(realm.id, entityManager).takeUnless { it.isEmpty() }?.also { rolesAfterUpdate ->
                LOGGER.info("Updating changed users role mapping ids for realm '$realmName'...")
                updateRoleMappings(rolesBeforeUpdate, rolesAfterUpdate, entityManager)
            }
            getGroups(realm.id, entityManager).takeUnless { it.isEmpty() }?.also { groupsAfterUpdate ->
                LOGGER.info("Updating changed users group membership ids for realm '$realmName'...")
                updateGroupMemberships(groupsBeforeUpdate, groupsAfterUpdate, entityManager)
            }
            LOGGER.info("Removing users role mapping orphans for realm '$realmName'...")
            removeUserRoleMappingOrphans(entityManager)
            LOGGER.info("Removing users group membership orphans for realm '$realmName'...")
            removeUserGroupMembershipOrphans(entityManager)
        } else {
            LOGGER.info("Realm '$realmName' is absent. Creating new realm...")
            realmManager.importRealm(rep, true)
        }
        return true
    }

    private fun getRoles(realmId: String, entityManager: EntityManager): Map<String, List<RoleEntity>> =
        entityManager.createQuery(SELECT_ROLES, RoleEntity::class.java)
            .setParameter("realmId", realmId)
            .resultStream
            .collect(
                Collectors.groupingBy(
                    { e: RoleEntity -> e.name },
                    Collectors.mapping({ e: RoleEntity -> e }, Collectors.toList())
                )
            )

    private fun getGroups(realmId: String, entityManager: EntityManager): Map<String, List<GroupEntity>> =
        entityManager.createQuery(SELECT_GROUPS, GroupEntity::class.java)
            .setParameter("realmId", realmId)
            .resultStream
            .collect(
                Collectors.groupingBy(
                    { e: GroupEntity -> e.name },
                    Collectors.mapping({ e: GroupEntity -> e }, Collectors.toList())
                )
            )

    private fun updateRoleMappings(
        beforeUpdate: Map<String, List<RoleEntity>>,
        afterUpdate: Map<String, List<RoleEntity>>,
        entityManager: EntityManager
    ) {
        beforeUpdate.forEach { (roleName, oldRoles) ->
            afterUpdate[roleName]?.also { newRoles ->
                oldRoles.forEach { oldRole ->
                    (if (oldRole.clientId == null) {
                        newRoles.find { newRole -> oldRole.id != newRole.id }
                    } else {
                        newRoles.find { newRole -> oldRole.clientId == newRole.clientId && oldRole.id != newRole.id }
                    })?.also { newRole ->
                        LOGGER.debug("Changed role id '${oldRole.id}' -> '${newRole.id}'")
                        entityManager.createNativeQuery(UPDATE_USER_ROLE_MAPPING_QUERY)
                            .setParameter(1, newRole.id)
                            .setParameter(2, oldRole.id)
                            .executeUpdate()
                    }
                }
            }
        }
    }

    private fun updateGroupMemberships(
        beforeUpdate: Map<String, List<GroupEntity>>,
        afterUpdate: Map<String, List<GroupEntity>>,
        entityManager: EntityManager
    ) {
        beforeUpdate.forEach { (groupName, oldGroup) ->
            afterUpdate[groupName]?.also { newGroups ->
                oldGroup.forEach { oldGroup ->
                    newGroups.find { newGroup -> oldGroup.id != newGroup.id }?.also { newGroup ->
                        LOGGER.debug("Changed group id '${oldGroup.id}' -> '${newGroup.id}'")
                        entityManager.createNativeQuery(UPDATE_USER_GROUP_MEMBERSHIP_QUERY)
                            .setParameter(1, newGroup.id)
                            .setParameter(2, oldGroup.id)
                            .executeUpdate()
                    }
                }
            }
        }
    }

    private fun removeUserRoleMappingOrphans(entityManager: EntityManager) {
        entityManager.createNativeQuery(DELETE_USER_ROLE_MAPPING_ORPHANS_QUERY).executeUpdate()
    }

    private fun removeUserGroupMembershipOrphans(entityManager: EntityManager) {
        entityManager.createNativeQuery(DELETE_USER_GROUP_MEMBERSHIP_ORPHANS_QUERY).executeUpdate()
    }

    private fun removeRealm(realmId: String, entityManager: EntityManager, session: KeycloakSession) {
        entityManager.find(RealmEntity::class.java, realmId, LockModeType.PESSIMISTIC_WRITE)?.let { realm ->
            entityManager.refresh(realm)
            val realmAdapter = RealmAdapter(session, entityManager, realm)

            realm.defaultGroupIds.clear()
            entityManager.flush()

            entityManager.createNamedQuery("deleteGroupRoleMappingsByRealm")
                .setParameter("realm", realm.id).executeUpdate()
            entityManager.flush()

            // remove clients
            val clientsIds = entityManager.createNamedQuery("getClientIdsByRealm", String::class.java)
                .setParameter("realm", realm.id)
                .resultList
            for (clientId in clientsIds) {
                val clientEntity: ClientEntity =
                    entityManager.find(ClientEntity::class.java, clientId, LockModeType.PESSIMISTIC_WRITE)
                val client = ClientAdapter(realmAdapter, entityManager, session, clientEntity)

                session.keycloakSessionFactory.publish(object : ClientModel.ClientRemovedEvent {
                    override fun getClient(): ClientModel {
                        return client
                    }

                    override fun getKeycloakSession(): KeycloakSession {
                        return session
                    }
                })
                // remove client roles
                StreamsUtil.closing(
                    PaginationUtils.paginateQuery(
                        entityManager.createNamedQuery("getClientRoles", RoleEntity::class.java)
                            .setParameter("client", clientEntity.id), null, null
                    ).resultStream
                )
                    .forEach { role -> removeRole(entityManager, role) }

                entityManager.createNamedQuery("deleteClientScopeClientMappingByClient")
                    .setParameter("clientId", clientEntity.id)
                    .executeUpdate()
                entityManager.remove(clientEntity)
                entityManager.flush()
            }

            entityManager.createNamedQuery("deleteDefaultClientScopeRealmMappingByRealm")
                .setParameter("realm", realm).executeUpdate()

            // remove client scopes
            entityManager.createNamedQuery("getClientScopeIds", String::class.java)
                .setParameter("realm", realm.id)
                .resultStream.forEach { clientScopeId ->
                    entityManager.find(ClientScopeEntity::class.java, clientScopeId, LockModeType.PESSIMISTIC_WRITE)
                        .also { clientScopeEntity ->
                            ClientScopeAdapter(
                                realmAdapter,
                                entityManager,
                                session,
                                clientScopeEntity
                            ).also { clientScopeAdapter ->
                                realmAdapter.removeDefaultClientScope(clientScopeAdapter)
                                entityManager.createNamedQuery("deleteClientScopeClientMappingByClientScope")
                                    .setParameter("clientScopeId", clientScopeId)
                                    .executeUpdate()
                                entityManager.createNamedQuery("deleteClientScopeRoleMappingByClientScope")
                                    .setParameter("clientScope", clientScopeEntity)
                                    .executeUpdate()
                                entityManager.remove(clientScopeEntity)
                                entityManager.flush()
                            }
                        }
                }
            // remove realm roles
            StreamsUtil.closing(
                PaginationUtils.paginateQuery(
                    entityManager.createNamedQuery("getRealmRoles", RoleEntity::class.java)
                        .setParameter("realm", realm.id), null, null
                ).resultStream
            )
                .forEach { role -> removeRole(entityManager, role) }
            // remove groups
            StreamsUtil.closing(PaginationUtils.paginateQuery(
                entityManager.createNamedQuery("getGroupIdsByParentAndNameContaining", String::class.java)
                    .setParameter("realm", realm.id)
                    .setParameter("parent", GroupEntity.TOP_PARENT_ID)
                    .setParameter("search", ""), null, null
            )
                .resultStream
                .map { id ->
                    GroupAdapter(
                        realmAdapter,
                        entityManager,
                        entityManager.find(GroupEntity::class.java, id)
                    )
                }
                .sorted(GroupModel.COMPARE_BY_NAME)
            ).forEach { group ->
                realmAdapter.removeDefaultGroup(group)
                StreamsUtil.closing(PaginationUtils.paginateQuery(
                    entityManager.createNamedQuery("getGroupIdsByParent", String::class.java)
                        .setParameter("realm", realm.id)
                        .setParameter("parent", group.id), null, null
                )
                    .resultStream
                    .map { id ->
                        GroupAdapter(
                            realmAdapter,
                            entityManager,
                            entityManager.find(GroupEntity::class.java, id)
                        )
                    }
                ).forEach { subGroup ->
                    realmAdapter.removeDefaultGroup(subGroup)
                    val groupEntity: GroupEntity =
                        entityManager.find(GroupEntity::class.java, subGroup.id, LockModeType.PESSIMISTIC_WRITE)
                    entityManager.createNamedQuery("deleteGroupRoleMappingsByGroup").setParameter("group", groupEntity)
                        .executeUpdate()
                    entityManager.remove(groupEntity)
                }
                val groupEntity: GroupEntity =
                    entityManager.find(GroupEntity::class.java, group.id, LockModeType.PESSIMISTIC_WRITE)
                entityManager.createNamedQuery("deleteGroupRoleMappingsByGroup").setParameter("group", groupEntity)
                    .executeUpdate()
                entityManager.remove(groupEntity)
                entityManager.flush()
            }

            entityManager.createNamedQuery("removeClientInitialAccessByRealm")
                .setParameter("realm", realm).executeUpdate()

            entityManager.remove(realm)
            entityManager.flush()
            entityManager.clear()

            session.keycloakSessionFactory.publish(object : RealmModel.RealmRemovedEvent {
                override fun getRealm(): RealmModel {
                    return realmAdapter
                }

                override fun getKeycloakSession(): KeycloakSession {
                    return session
                }
            })
        }
    }

    private fun removeRole(entityManager: EntityManager, role: RoleEntity) {
        val compositeRoleTable = JpaUtils.getTableNameForNativeQuery("COMPOSITE_ROLE", entityManager)
        entityManager.createNativeQuery("delete from $compositeRoleTable where CHILD_ROLE = ?1")
            .setParameter(1, role.id)
            .executeUpdate()
        entityManager.createNamedQuery("deleteClientScopeRoleMappingByRole").setParameter("role", role)
            .executeUpdate()
        entityManager.flush()
        entityManager.remove(role)
    }

    private companion object {

        private const val SELECT_ROLES = "select role from RoleEntity role where role.realmId = :realmId"
        private const val SELECT_GROUPS = "select group from GroupEntity group where group.realm = :realmId"
        private const val UPDATE_USER_ROLE_MAPPING_QUERY =
            "update user_role_mapping set role_id = ?1 where role_id = ?2"
        private const val UPDATE_USER_GROUP_MEMBERSHIP_QUERY =
            "update user_group_membership set group_id = ?1 where group_id = ?2"
        private const val DELETE_USER_ROLE_MAPPING_ORPHANS_QUERY =
            "delete from user_role_mapping where role_id not in (select id from keycloak_role)"
        private const val DELETE_USER_GROUP_MEMBERSHIP_ORPHANS_QUERY =
            "delete from user_group_membership where group_id not in (select id from keycloak_group)"

        private val LOGGER = Logger.getLogger(CustomImportProviderImpl::class.java)
    }
}