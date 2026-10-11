package storage

// schemaSQL initializes version 1 atomically, only for an uninitialized database.
const schemaSQL = `CREATE TABLE IF NOT EXISTS libraries (
 id TEXT PRIMARY KEY, root TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
 identity TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1,
 healthy INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0,
 epoch INTEGER NOT NULL DEFAULT 0, round INTEGER NOT NULL DEFAULT 0, applied_round INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS directories (
 id INTEGER PRIMARY KEY, library_id TEXT NOT NULL REFERENCES libraries(id),
 parent_id INTEGER REFERENCES directories(id), relative TEXT NOT NULL,
 name TEXT NOT NULL, identity TEXT NOT NULL DEFAULT '',
 generation INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0,
 state TEXT NOT NULL DEFAULT 'visible', missing_streak INTEGER NOT NULL DEFAULT 0, missing_epoch INTEGER NOT NULL DEFAULT 0, group_revision INTEGER NOT NULL DEFAULT -1, group_token TEXT NOT NULL DEFAULT '',
 observed_round INTEGER NOT NULL DEFAULT 0, UNIQUE(library_id,relative)
);
CREATE INDEX IF NOT EXISTS directory_children ON directories(parent_id,state,name,id);
CREATE INDEX IF NOT EXISTS directory_library ON directories(library_id,id);
CREATE INDEX IF NOT EXISTS directory_missing ON directories(library_id,observed_round,id);
CREATE TABLE IF NOT EXISTS media (
 id TEXT PRIMARY KEY, directory_id INTEGER NOT NULL REFERENCES directories(id),
 name TEXT NOT NULL, UNIQUE(directory_id,name)
);
CREATE INDEX IF NOT EXISTS media_directory ON media(directory_id,id);
CREATE TABLE IF NOT EXISTS versions (
 media_id TEXT NOT NULL REFERENCES media(id), version TEXT NOT NULL,
 fingerprint TEXT NOT NULL, size INTEGER NOT NULL, mtime INTEGER NOT NULL,
 kind TEXT NOT NULL, width INTEGER NOT NULL DEFAULT 1, height INTEGER NOT NULL DEFAULT 1,
 duration_ms INTEGER NOT NULL DEFAULT 0, rotation INTEGER NOT NULL DEFAULT 0,
 captured_raw TEXT NOT NULL DEFAULT '', captured_source TEXT NOT NULL DEFAULT 'mtime',
 effective_time INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'discovered',
 error TEXT NOT NULL DEFAULT '', PRIMARY KEY(media_id,version)
);
CREATE TABLE IF NOT EXISTS entries (
 directory_id INTEGER NOT NULL REFERENCES directories(id), generation INTEGER NOT NULL,
 media_id TEXT NOT NULL, version TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
 effective_time INTEGER NOT NULL, mtime INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'visible', missing_streak INTEGER NOT NULL DEFAULT 0,
 observed_round INTEGER NOT NULL DEFAULT 0, present INTEGER NOT NULL DEFAULT 1, missing_epoch INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(directory_id,generation,media_id),
 FOREIGN KEY(media_id,version) REFERENCES versions(media_id,version)
);
CREATE INDEX IF NOT EXISTS entry_capture ON entries(directory_id,generation,state,effective_time,media_id);
CREATE INDEX IF NOT EXISTS entry_mtime ON entries(directory_id,generation,state,mtime,media_id);
CREATE INDEX IF NOT EXISTS entry_name ON entries(directory_id,generation,state,name,media_id);
CREATE INDEX IF NOT EXISTS entry_kind_capture ON entries(directory_id,generation,state,kind,effective_time,media_id);
CREATE INDEX IF NOT EXISTS entry_kind_mtime ON entries(directory_id,generation,state,kind,mtime,media_id);
CREATE INDEX IF NOT EXISTS entry_kind_name ON entries(directory_id,generation,state,kind,name,media_id);
CREATE INDEX IF NOT EXISTS entry_version ON entries(media_id,version,directory_id,generation);
CREATE TABLE IF NOT EXISTS builds (
 directory_id INTEGER NOT NULL REFERENCES directories(id), generation INTEGER NOT NULL,
 base_generation INTEGER NOT NULL, base_revision INTEGER NOT NULL,
 PRIMARY KEY(directory_id,generation)
);
CREATE TABLE IF NOT EXISTS jobs (
 id INTEGER PRIMARY KEY, media_id TEXT NOT NULL, version TEXT NOT NULL,
 kind TEXT NOT NULL, parameters TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'queued',
 due INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, lease INTEGER NOT NULL DEFAULT 0,
 priority INTEGER NOT NULL DEFAULT 0, first_observed INTEGER NOT NULL DEFAULT 0, fingerprint TEXT NOT NULL DEFAULT '',
 error TEXT NOT NULL DEFAULT '', UNIQUE(media_id,version,kind,parameters),
 FOREIGN KEY(media_id,version) REFERENCES versions(media_id,version)
);
CREATE INDEX IF NOT EXISTS job_claim ON jobs(kind,state,due,id);
CREATE INDEX IF NOT EXISTS job_version ON jobs(media_id,version,state);
CREATE TABLE IF NOT EXISTS thumbnails (
 key TEXT PRIMARY KEY, media_id TEXT NOT NULL, version TEXT NOT NULL,
 size INTEGER NOT NULL, accessed INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'ready',
 FOREIGN KEY(media_id,version) REFERENCES versions(media_id,version)
);
CREATE INDEX IF NOT EXISTS thumbnail_lru ON thumbnails(state,accessed,key);
CREATE INDEX IF NOT EXISTS thumbnail_version ON thumbnails(media_id,version);
CREATE TABLE IF NOT EXISTS counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT INTO counters(name,value) VALUES('thumbnail_bytes',0) ON CONFLICT(name) DO NOTHING;
PRAGMA user_version=1;

CREATE TABLE group_anchors(directory_id INTEGER NOT NULL REFERENCES directories(id), token TEXT NOT NULL, sort TEXT NOT NULL, direction TEXT NOT NULL, time_key INTEGER NOT NULL, name_key TEXT NOT NULL, media_id TEXT NOT NULL, PRIMARY KEY(directory_id,token,sort,direction,media_id));
CREATE INDEX group_anchor_time ON group_anchors(directory_id,token,sort,direction,time_key,media_id);
CREATE INDEX group_anchor_name ON group_anchors(directory_id,token,sort,direction,name_key,media_id);

CREATE INDEX directory_dirty_groups ON directories(id) WHERE state='visible' AND group_revision!=revision;
`
