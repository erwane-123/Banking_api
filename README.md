# API bancaire Java

Version Java simple du projet bancaire, sans framework externe, avec `HttpServer` du JDK.

## Lancement

```bash
cd api-java
javac -d out src/Main.java
java -cp out Main 8002
```

## Accès

- API : `http://127.0.0.1:8002`
- Swagger : `http://127.0.0.1:8002/docs`

## Endpoints

- `GET /`
- `POST /accounts`
- `GET /accounts`
- `GET /accounts/{account_id}`
- `POST /accounts/{account_id}/deposit`
- `POST /accounts/{account_id}/withdraw`
- `GET /accounts/{account_id}/transactions`

## Limite

Les donnees sont conservees en memoire pendant l'execution du serveur.
