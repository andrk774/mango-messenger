ALTER TABLE server_settings ADD COLUMN kubernetes_config text NOT NULL DEFAULT
'{"context":"docker-desktop","namespace":"mango-dev","deployment":"backend","service":"backend"}';
