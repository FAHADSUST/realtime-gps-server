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
        },
      },
    },
  },
}
