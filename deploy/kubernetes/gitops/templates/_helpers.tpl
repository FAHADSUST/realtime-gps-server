{{/*
Settings every Application in this chart shares: the platform project, this cluster as the
destination, automated sync that also prunes and repairs drift, and retries with back-off.
Usage: {{ include "gitops.applicationSpec" (dict "namespace" "monitoring") | nindent 2 }}
Optional: "syncOptions" (a list) adds to the default sync options.
*/}}
{{- define "gitops.applicationSpec" -}}
project: platform
destination:
  server: https://kubernetes.default.svc
  namespace: {{ .namespace }}
syncPolicy:
  automated:
    prune: true
    selfHeal: true
  syncOptions:
    - CreateNamespace=true
    - ServerSideApply=true
    {{- range .syncOptions }}
    - {{ . }}
    {{- end }}
  retry:
    limit: 5
    backoff:
      duration: 10s
      factor: 2
      maxDuration: 3m
{{- end -}}

{{/*
Ingress settings for an operator UI without a login of its own: a certificate from the cluster
issuer and basic authentication against the ops-ui-basic-auth Secret Ansible keeps in the namespace.
Usage: {{ include "gitops.opsUiIngress" (dict "host" "prometheus" "root" $) | nindent 8 }}
*/}}
{{- define "gitops.opsUiIngress" -}}
annotations:
  cert-manager.io/cluster-issuer: {{ .root.Values.cluster.issuer }}
  nginx.ingress.kubernetes.io/auth-type: basic
  nginx.ingress.kubernetes.io/auth-secret: ops-ui-basic-auth
  nginx.ingress.kubernetes.io/auth-realm: {{ printf "%s operators" .root.Values.cluster.name | quote }}
hosts:
  - {{ printf "%s.%s" .host .root.Values.cluster.domain }}
tls:
  - secretName: {{ printf "%s-tls" .host }}
    hosts:
      - {{ printf "%s.%s" .host .root.Values.cluster.domain }}
{{- end -}}
