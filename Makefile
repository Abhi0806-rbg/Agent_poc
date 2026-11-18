up:      ; docker compose up --build
down:    ; docker compose down
reset:   ; docker compose down -v
test:    ; cd backend && mvn -q test
dev-db:  ; docker compose up db
dev-api: ; cd backend && mvn spring-boot:run
dev-ui:  ; cd frontend && npm install && npm run dev
