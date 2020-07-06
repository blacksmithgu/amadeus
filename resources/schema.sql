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
    -- The 'canonical' name of this song; it may have aliases.
    name TEXT NOT NULL,
    -- When this song was added to the database.
    upload_time TEXT NOT NULL,
    -- How did we get this song? Direct upload (0), youtube (1), etc.
    upload_origin INTEGER NOT NULL CHECK (upload_origin >= 0 AND upload_origin <= 1),
    -- The URL of this song if it came from an online source; otherwise, it's original filename.
    upload_data_source TEXT NOT NULL,
    -- The file location of the song data.
    filename TEXT NOT NULL
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

-- All of the queued song downloads; actual song requests will be pulled from an in-memory queue, but they are
-- also stored in this table for durability reasons (in case of shutdown/crash). Entries are deleted from this
-- table once they have completed.
CREATE TABLE queued_song_downloads (
    -- The ID of this song queue; this is equivalent to the sqlite ROWID field.
    id INTEGER PRIMARY KEY,
    -- The url to download the song from.
    url TEXT NOT NULL,
    -- The time that this download was requested.
    request_time TEXT NOT NULL
);

-- All completed song downloads; i.e., finished downloads from queued_song_downloads. Contains extracted metadata
-- from the download to simplify conversion into an actual song. Entries are deleted from this table once they have
-- been successfully converted into a song (or acknowledged as an error).
CREATE TABLE completed_song_downloads (
    -- The ID of this completed song download.
    id INTEGER PRIMARY KEY,
    -- The URL that the song was downloaded from.
    url TEXT NOT NULL,
    -- The time that the download was initially requested.
    request_time TEXT NOT NULL,
    -- The time that the download and metadata extraction was completed.
    completed_time TEXT NOT NULL,
    -- The extracted title of the song.
    title TEXT,
    -- The extracted artist of the song.
    artist TEXT,
    -- The extracted album the song belongs to.
    album TEXT,
    -- The URL of the thumbnail for the video this song came from.
    thumbnail_url TEXT,
    -- The length of this song, in seconds.
    duration_seconds INTEGER,
    -- The location of the file (i.e., where it was downloaded to)
    filename TEXT,
    -- If this song download resulted in an error, this is non-null and the error details.
    error TEXT
);
