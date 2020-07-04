-- Core schema used by the SQLite server for storing song and user metadata.

-- Sources of music: games, anime, authors, whatever.
CREATE TABLE sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- The name of this source ("Dark Souls 1", "Bob Ross").
    name TEXT NOT NULL,
    -- What kind of source is this? 'game', 'artist', 'movie', etc.
    type TEXT NOT NULL,
    -- A link describing more about this source (an anilist link, a steam link, wikipedia, whatever).
    reference_link TEXT,
    -- The time when this source was created.
    created_time TEXT NOT NULL
);

-- TODO: Add a pretty image or something.
-- File data is stored on the filesystem, named the same as the ID of the song.
CREATE TABLE songs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- When this song was added to the database.
    upload_time TEXT NOT NULL,
    -- How did we get this song? Direct upload (0), youtube (1), etc.
    upload_origin INTEGER NOT NULL CHECK (upload_origin >= 0 AND upload_origin <= 1),
    -- The URL of this song if it came from an online source; otherwise, it's original filename.
    upload_data_source TEXT NOT NULL
);

-- Maps songs to sources (and annotates what type of connection it is).
CREATE TABLE songs_sources (
    song_id INTEGER NOT NULL,
    source_id INTEGER NOT NULL,
    -- What type of connection is this? ('author', 'insert-in', 'boss-music', etc)
    type TEXT NOT NULL,
    -- TODO: Must explicitly enable foreign keys in SQLite via https://www.sqlite.org/foreignkeys.html.
    FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE CASCADE,
    FOREIGN KEY(source_id) REFERENCES sources(id) ON DELETE CASCADE
);