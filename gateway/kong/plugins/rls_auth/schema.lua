local typedefs = require "kong.db.schema.typedefs"

-- Configuration accepted by the rls_auth plugin. Kong validates declarative config against this,
-- so a typo in kong.yml fails at startup rather than on the first request.
return {
  name = "rls_auth",
  fields = {
    { consumer = typedefs.no_consumer },
    { protocols = typedefs.protocols_http },
    {
      config = {
        type = "record",
        fields = {
          -- The Id service's restricted port: /api/v1/internal/** is served only there.
          {
            authenticate_url = {
              type = "string",
              required = true,
              default = "http://id-service:9081/api/v1/internal/authenticate",
            },
          },
          -- Short by design: a slow Id service must fail fast rather than hold every request open.
          { connect_timeout = { type = "integer", default = 1000, between = { 1, 60000 } } },
          { send_timeout = { type = "integer", default = 1000, between = { 1, 60000 } } },
          { read_timeout = { type = "integer", default = 2000, between = { 1, 60000 } } },

          -- Upper bound on how long a cached "yes" survives, and therefore on how long a disabled
          -- user keeps working. Capped at five minutes so no deployment can choose a careless value.
          { max_cache_ttl = { type = "integer", default = 60, between = { 1, 300 } } },
          -- A replayed bad token should not cost a round trip every time, but this stays short so a
          -- user whose access was just fixed is not locked out for long.
          { negative_cache_ttl = { type = "integer", default = 5, between = { 1, 60 } } },
        },
      },
    },
  },
}
