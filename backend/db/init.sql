CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email varchar(100) UNIQUE NOT NULL,
    password varchar(255) NOT NULL
);


-- CREATE TABLE admin (
--     id SERIAL PRIMARY KEY,
--     login VARCHAR(100) UNIQUE,
--     password VARCHAR(100) UNIQUE
-- );

-- INSERT INTO admin (login, password) VALUES ('root', 'admin');


-- CREATE TABLE users (
--     id SERIAL PRIMARY KEY,
--     username VARCHAR(50) UNIQUE,
--     name VARCHAR(50),
--     surname VARCHAR(50),
--     email VARCHAR(100) UNIQUE NOT NULL,
--     is_verify BOOLEAN,
--     password VARCHAR(255),
--     role VARCHAR(50) CHECK (role IN ('admin', 'user', 'organizer')),
--     status VARCHAR(50) CHECK (status IN ('active', 'inactive', 'suspended')) NOT NULL,
--     created_dttm TIMESTAMP DEFAULT NOW(),
--     updated_dttm TIMESTAMP DEFAULT NOW()
-- );


-- CREATE TABLE organizer_legal_info (
--     id SERIAL PRIMARY KEY,
--     user_id INTEGER REFERENCES users(id),
--     company_name VARCHAR(100),
--     contact_number VARCHAR(50),
--     approved BOOLEAN,
--     bank_account VARCHAR(50),
--     created_dttm TIMESTAMP DEFAULT NOW(),
--     updated_dttm TIMESTAMP DEFAULT NOW()
-- );

-- CREATE TABLE organizer_bank_details (
--     id SERIAL PRIMARY KEY,
--     legal_info_id INTEGER REFERENCES organizer_legal_info(id) NOT NULL,
--     bank_name VARCHAR(100) NOT NULL,
--     account_number VARCHAR(50) NOT NULL,
--     routing_number VARCHAR(50) NOT NULL,
--     swift_code VARCHAR(50),
--     created_dttm TIMESTAMP DEFAULT NOW(),
--     updated_dttm TIMESTAMP DEFAULT NOW()
-- );

-- CREATE TABLE matches (
--     id SERIAL PRIMARY KEY,
--     league VARCHAR(100),
--     uuid VARCHAR(255),
--     schedule_dt VARCHAR(100) NOT NULL,
--     schedule_time_lcl VARCHAR(100) NOT NULL,
--     stadium_name VARCHAR(255),
--     -- stadium_id INTEGER REFERENCES stadiums(id) NOT NULL,
--     tickets_cnt INTEGER NOT NULL,
--     info TEXT,
--     team_home_name VARCHAR(255) NOT NULL,
--     team_away_name VARCHAR(255) NOT NULL,
--     -- team_home_id INTEGER REFERENCES teams(id) NOT NULL,
--     -- team_away_id INTEGER REFERENCES teams(id) NOT NULL,
--     photo_url VARCHAR(255),
--     organizer_id INTEGER NOT NULL, -- REFERENCES users(id)
--     status VARCHAR(50) CHECK (status IN ('scheduled', 'ongoing', 'completed', 'cancelled')),
--     created_dttm TIMESTAMP DEFAULT NOW(),
--     updated_dttm TIMESTAMP DEFAULT NOW()
-- );

-- CREATE TABLE stadiums(
--     id SERIAL PRIMARY KEY,
--     name VARCHAR(100),
--     city VARCHAR(50)
-- );

-- CREATE TABLE seats (
--     id SERIAL PRIMARY KEY,
--     row INTEGER,
--     sector VARCHAR(255),
--     seat_number VARCHAR(100),
--     match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
--     price DECIMAL(12,2),
--     stadium_id INTEGER NOT NULL REFERENCES stadiums(id) ON DELETE CASCADE,
--     status VARCHAR(50)
-- );

-- CREATE TABLE tickets (
--     id SERIAL PRIMARY KEY,
--     seat_id INTEGER  NOT NULL REFERENCES seats(id) ON DELETE CASCADE,
--     user_id INTEGER  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
--     match_id INTEGER NOT NULL REFERENCES matches(id),
--     price INTEGER,
--     created_at TIMESTAMP DEFAULT NOW(),
--     updated_at TIMESTAMP,
--     statuc VARCHAR(50)
-- );


