# Introduction

The project is a keycloak extension that provides fast realms configuration import.

# System requirements

- JDK 11
- Gradle
- Keycloak 24.0.3

# Working principles

Default mechanism of import realm configuration from a file firstly removes realm users from a database before create a new realm. After the new realm creation users have to created again in the database from files. It may take significant time depends on count of importing users.
This project offers custom import approach without removing users from the database. The main way of custom import approach is in managing user relations with other entities of database. 

# Features

- Import realms configuration without removing users from a database which provides fast update;
- Support changing internal user relations IDs. It means that all changed internal IDs of user-related entities in realm configuration file will be updated for all users and relations will be saved;
- Support old-style JS policies upload from realm configuration files;
- Support absent users import from separated files;

# How to use

1) Pull this project
2) Set your database connection properties with in `environment` block inside file `./compose/docker-compose.yml`
3) Put your realm configuration file into `./compose/realms` directory. Realm configuration file must named like `<realm>-realm.json`. You can specify realm name for import in `-Dkeycloak.migration.realmName` parameter.
4) If needed put realm users file into `./compose/realms` directory. Realm users file must named like `<realm>-users-0.json`. You can enable users import by `-Dkeycloak.migration.withUsers=true` parameter set (it `false` by default).
5) Run gradle task `composeUp`

# Performance comparison

To compare custom import approach to default keycloak import some test have done. Tests have shown that the number of users does not affect the time of custom import.

Hardware:
2,6 GHz 6-Core Intel Core i7;
16 GB RAM;
512 GB SSD

Input data:
docker image `bitnami/keycloak` version `24.0.3` connected to PostgreSQL database.
10000(50000) users in database with 2 roles and 1 group assigned each. Users to default keycloak `dir` import located in 10(50) files accordingly with 1000 users each.

|           | Default keycloak import | Custom import |
|-----------|:-----------------------:|:-------------:|
| 10k users |     ~ 5 min 40 sec      |   ~ 20 sec    |
| 50k users |     ~ 23 min 12 sec     |   ~ 20 sec    |
