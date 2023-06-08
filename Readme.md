# Introduction

The project is a keycloak extension that provides fast realms configuration update.

# System requirements

- JDK 11
- Gradle
- Keycloak 16.1.1

# Working principles

Default mechanism of import realm configuration from a file firstly removes realm users from a database before create a new realm. After the new realm creation users have to created again in the database from files. It may take significant time depends on count of importing users.
This project offers custom import approach without removing users from the database. The main way of custom import approach is in managing user relations with other entities of database. 

# Features

- Import realms configuration without removing users from a database which provides fast update;
- Support changing internal user relations IDs. It means that all changed internal IDs of user-related entities in realm configuration file will be updated for all users and relations will be saved;

# How to use

1) Pull this project
2) Set your database connection properties with `DB_` prefix into file `./compose/.env`
3) Put your realm configuration file into `./compose/realms` directory. Realm configuration file must named like `<realm>-realm.json`
4) Run gradle task `composeUp`

Project supports a PostgreSQL database. If you need to connect to other database vendor you have to add an appropriate driver dependency into `build.gradle` and change `DB_VENDOR` property into `.env` file accordingly.

# Export realm configuration

If you are using keycloak in docker container:

1) Execute next command `docker exec -it <container_name> /opt/jboss/keycloak/bin/standalone.sh -Djboss.socket.binding.port-offset=100 -Dkeycloak.migration.action=export -Dkeycloak.migration.provider=dir -Dkeycloak.migration.realmName=<realm_name> -Dkeycloak.migration.usersExportStrategy=SKIP -Dkeycloak.migration.dir=/tmp/realm_export_config/ -Djboss.as.management.blocking.timeout=3600`
2) After you see next text `Admin console listening on...` in your console log you may interrupt execution (Ctrl+C) or move to new console window 
3) Execute next command `docker cp keycloak:/tmp/realm_export_config/ <your_target_dir>`

# Performance comparison

To compare custom import approach to default keycloak import some test have done. Tests have shown that the number of users does not affect the time of custom import.

Hardware:
2,6 GHz 6-Core Intel Core i7;
16 GB RAM;
512 GB SSD

Input data:
docker image `jboss/keycloak` version 16.1.1 connected to PostgreSQL database.
10000(50000) users in database with 2 roles and 1 group assigned each. Users to default keycloak import located in 10(50) files accordingly with 1000 users each.

|           | Default keycloak import | Custom import |
|-----------|:-----------------------:|:-------------:|
| 10k users |     ~ 5 min 40 sec      |    ~ 7 sec    |
| 50k users |     ~ 23 min 12 sec     |    ~ 7 sec    |
