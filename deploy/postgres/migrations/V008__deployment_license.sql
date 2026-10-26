create table if not exists deployment_licenses (
  organization_id varchar(36) primary key references organizations(id),
  installation_id varchar(36) not null unique,
  payload text,
  signature varchar(128),
  customer_name varchar(80),
  not_before timestamp,
  not_after timestamp,
  seats integer,
  sharing boolean,
  model_gateway boolean,
  session_view boolean,
  uploaded_at timestamp
);
