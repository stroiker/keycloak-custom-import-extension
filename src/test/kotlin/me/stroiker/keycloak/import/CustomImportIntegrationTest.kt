package me.stroiker.keycloak.import

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.CreatedResponseUtil
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomImportIntegrationTest {

    val network = Network.newNetwork()

    @Container
    val postgresCnt = GenericContainer<GenericContainer<*>>(
        ImageFromDockerfile()
            .withDockerfileFromBuilder { builder: DockerfileBuilder ->
                builder
                    .from("postgres:12-alpine")
                    .env("POSTGRES_DB", POSTGRES_DATABASE)
                    .env("POSTGRES_USER", POSTGRES_USER)
                    .env("POSTGRES_PASSWORD", POSTGRES_PASSWORD)
                    .expose(5432)
                    .build()
            }
    )
        .withExposedPorts(5432)
        .withNetwork(network)
        .withNetworkAliases(POSTGRES_CNT_NAME)

    @Test
    fun `should import configuration without user loss`() {
        val realmRoleId: String
        val userId: String
        var keycloakCnt = createKeycloakCnt(DIR_IMPORT_PROVIDER).apply { start() }
        var adminClient = creteAdminClient(String.format(KEYCLOAK_PATH, keycloakCnt.firstMappedPort))
        adminClient.realm(REALM_NAME).also { realm ->
            realm.clients().findByClientId(CLIENT_NAME).first().id.also { clientId ->
                // create user
                userId = UserRepresentation().apply {
                    email = USER_EMAIL
                    username = USER_EMAIL
                    isEnabled = true
                }.let { userRep ->
                    CreatedResponseUtil.getCreatedId(realm.users().create(userRep))
                }
                // create new realm role
                val newRealmRole = RoleRepresentation(
                    NEW_REALM_ROLE_NAME,
                    "Test role that should be removed after custom import",
                    true
                )
                realm.roles().create(newRealmRole)
                // assign existing roles and groups to user
                realm.users().get(userId).also { user ->
                    // ...realm roles
                    realm.roles().list().filter { it.name in listOf(REALM_ROLE_NAME, NEW_REALM_ROLE_NAME) }
                        .also { realmRoles ->
                            realmRoleId = realmRoles.find { it.name == REALM_ROLE_NAME }!!.id
                            user.roles().realmLevel().add(realmRoles)
                        }
                    // ...client role
                    realm.clients().get(clientId).roles().get(CLIENT_ROLE_NAME).toRepresentation()
                        .also { clientRole ->
                            user.roles().clientLevel(clientId).add(listOf(clientRole))
                        }
                    // ...group
                    realm.groups().groups(GROUP_NAME, 0, 1).first().also { group ->
                        user.joinGroup(group.id)
                    }
                }

                // check user assignee before import
                realm.users().also { users ->
                    assertEquals(1, users.count())
                    users.get(userId).also { user ->
                        assertEquals(userId, user.toRepresentation().id)
                        user.roles().also { roles ->
                            roles.realmLevel().listAll().also { realmRoles ->
                                assertEquals(3, realmRoles.size)
                                assertNotNull(realmRoles.find { it.name == REALM_ROLE_NAME })
                                assertNotNull(realmRoles.find { it.name == NEW_REALM_ROLE_NAME })
                            }
                            roles.clientLevel(clientId).listAll().also { clientRoles ->
                                assertEquals(1, clientRoles.size)
                                assertNotNull(clientRoles.find { it.name == CLIENT_ROLE_NAME })
                            }
                        }
                        user.groups().also { groups ->
                            assertEquals(1, groups.size)
                            assertNotNull(groups.find { it.name == GROUP_NAME })
                        }
                    }
                }
            }
        }

        keycloakCnt.stop()
        keycloakCnt = createKeycloakCnt(CUSTOM_IMPORT_PROVIDER).apply { start() }

        adminClient = creteAdminClient(String.format(KEYCLOAK_PATH, keycloakCnt.firstMappedPort))
        adminClient.realm(REALM_NAME).also { realm ->
            realm.clients().findByClientId(CLIENT_NAME).first().id.also { clientId ->
                // check that realm role changed id after import
                realm.roles().get(REALM_ROLE_NAME).toRepresentation().also { realmRole ->
                    assertNotEquals(realmRoleId, realmRole.id)
                }
                // check user assignee after import
                realm.users().also { users ->
                    assertEquals(1, users.count())
                    users.get(userId).also { user ->
                        assertEquals(userId, user.toRepresentation().id)
                        user.roles().also { roles ->
                            roles.realmLevel().listAll().also { realmRoles ->
                                assertEquals(2, realmRoles.size)
                                assertNotNull(realmRoles.find { it.name == REALM_ROLE_NAME })
                                assertNull(realmRoles.find { it.name == NEW_REALM_ROLE_NAME })
                            }
                            roles.clientLevel(clientId).listAll().also { clientRoles ->
                                assertEquals(1, clientRoles.size)
                                assertNotNull(clientRoles.find { it.name == CLIENT_ROLE_NAME })
                            }
                        }
                        user.groups().also { groups ->
                            assertEquals(1, groups.size)
                            assertNotNull(groups.find { it.name == GROUP_NAME })
                        }
                    }
                }
            }
        }
    }

    private fun creteAdminClient(path: String) =
        KeycloakBuilder.builder()
            .serverUrl(path)
            .grantType(OAuth2Constants.PASSWORD)
            .realm("master")
            .clientId("admin-cli")
            .username(KEYCLOAK_ADMIN_USER)
            .password(KEYCLOAK_ADMIN_PASSWORD)
            .build()

    private fun createKeycloakCnt(importProvider: String): GenericContainer<*> =
        GenericContainer<GenericContainer<*>>(
            ImageFromDockerfile()
                .withDockerfileFromBuilder { builder: DockerfileBuilder ->
                    builder
                        .from("jboss/keycloak:$KEYCLOAK_VERSION")
                        .env("DB_USER", POSTGRES_USER)
                        .env("DB_PASSWORD", POSTGRES_PASSWORD)
                        .env("DB_VENDOR", "postgres")
                        .env("DB_ADDR", POSTGRES_CNT_NAME)
                        .env("DB_PORT", "5432")
                        .env("KEYCLOAK_USER", KEYCLOAK_ADMIN_USER)
                        .env("KEYCLOAK_PASSWORD", KEYCLOAK_ADMIN_PASSWORD)
                        .user("root")
                        .cmd(
                            "-b",
                            "0.0.0.0",
                            "-Dkeycloak.profile.feature.upload_scripts=enabled",
                            "-Dkeycloak.migration.provider=$importProvider",
                            "-Dkeycloak.migration.strategy=OVERWRITE_EXISTING",
                            "-Dkeycloak.migration.action=import",
                            "-Dkeycloak.migration.dir=/config"
                        )
                        .expose(8080)
                        .build()
                }
        )
            .withCopyFileToContainer(MountableFile.forHostPath("src/test/resources/"), "/config/")
            .withCopyFileToContainer(
                MountableFile.forHostPath("build/libs/"),
                "/opt/jboss/keycloak/standalone/deployments/"
            )
            .withExposedPorts(8080)
            .withNetwork(network)
            .dependsOn(postgresCnt)
            .withNetworkAliases("${importProvider}_import_keycloak")

    companion object {
        private const val KEYCLOAK_VERSION = "16.1.1"
        private const val KEYCLOAK_PATH = "http://localhost:%d/auth"

        private const val KEYCLOAK_ADMIN_USER = "admin"
        private const val KEYCLOAK_ADMIN_PASSWORD = "admin"
        private const val POSTGRES_CNT_NAME = "postgres"
        private const val POSTGRES_USER = "postgres"
        private const val POSTGRES_PASSWORD = "postgres"
        private const val POSTGRES_DATABASE = "keycloak"

        private const val REALM_NAME = "test_realm"
        private const val CLIENT_NAME = "test_client"
        private const val USER_EMAIL = "test_user@example.com"
        private const val REALM_ROLE_NAME = "TEST_REALM_ROLE"
        private const val NEW_REALM_ROLE_NAME = "NEW_TEST_REALM_ROLE"
        private const val CLIENT_ROLE_NAME = "TEST_CLIENT_ROLE"
        private const val GROUP_NAME = "TEST_GROUP"

        private const val DIR_IMPORT_PROVIDER = "dir"
        private const val CUSTOM_IMPORT_PROVIDER = "custom"
    }
}