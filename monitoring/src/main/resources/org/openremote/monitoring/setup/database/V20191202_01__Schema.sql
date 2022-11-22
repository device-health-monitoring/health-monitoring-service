/*
  ############################# EXTENSIONS #############################
 */
create extension if not exists POSTGIS;
create extension if not exists POSTGIS_TOPOLOGY;
create extension if not exists ltree;
/*
  ############################# SEQUENCES #############################
 */
create sequence OPENREMOTE_SEQUENCE
  start 1000
  increment 1;

/*
  ############################# TABLES #############################
 */

create table TENANT_RULESET (
  ID                    int8                     not null,
  CREATED_ON            timestamp with time zone not null,
  ENABLED               boolean                  not null,
  LAST_MODIFIED         timestamp with time zone not null,
  NAME                  varchar(255)             not null,
  RULES                 text                     not null,
  RULES_LANG            varchar(255)             not null default 'GROOVY',
  VERSION               int8                     not null,
  REALM                 varchar(255)             not null,
  ACCESS_PUBLIC_READ    boolean                  not null default false,
  META                  jsonb,
  primary key (ID)
);

create table NOTIFICATION (
  ID              int8                     not null,
  NAME            varchar(255),
  TYPE            varchar(50)              not null,
  TARGET          varchar(50)              not null,
  TARGET_ID       varchar(255)              not null,
  SOURCE          varchar(50)              not null,
  SOURCE_ID       varchar(43),
  MESSAGE         jsonb,
  ERROR           varchar(4096),
  SENT_ON         timestamp with time zone not null,
  DELIVERED_ON    timestamp with time zone,
  ACKNOWLEDGED_ON timestamp with time zone,
  ACKNOWLEDGEMENT varchar(255),
  primary key (ID)
);

create table SYSLOG_EVENT (
  ID          int8         not null,
  TIMESTAMP   timestamp with time zone not null,
  CATEGORY    varchar(255) not null,
  LEVEL       int4         not null,
  MESSAGE     varchar(131072),
  SUBCATEGORY varchar(1024),
  primary key (ID)
);


