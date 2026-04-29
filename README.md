# Spotify Sort

Spotify Sort is a non-commercial tool that scans your Spotify library, enriches tracks with Last.fm metadata, and proposes playlist updates before anything is written back.

## What It Includes

- Spring Boot backend in this repo
- React + Vite frontend in the sibling `../spotify-sort-fe` repo
- PostgreSQL for app data and cache

## Quick Start With Docker Compose

Place the two repos side by side:

- `spotify-sort`
- `spotify-sort-fe`

Set these environment variables in your shell before starting:

```bash
export SPOTIFY_CLIENT_ID=...
export SPOTIFY_CLIENT_SECRET=...
export LASTFM_API_KEY=...
```

Then run:

```bash
docker compose up --build
```

Open:

- Frontend: `http://127.0.0.1:3000`
- Backend API: `http://127.0.0.1:8080`
- PostgreSQL: `localhost:5432`

## Local Dev Without Docker

Backend:

```bash
./mvnw spring-boot:run
```

Frontend:

```bash
cd ../spotify-sort-fe
npm install
npm run dev
```

## Notes

- Spotify OAuth callback defaults to `http://127.0.0.1:8080/api/auth/callback`
- The frontend proxies `/api` calls to the backend
- This project uses the Spotify Web API and Last.fm API and is not endorsed or certified by either provider