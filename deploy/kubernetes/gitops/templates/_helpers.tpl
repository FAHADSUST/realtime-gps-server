{{/*
Settings every Application in this chart shares: the platform project, this cluster as the
destination, automated sync that also prunes and repairs drift, and retries with back-off.
Usage: {{ include "gitops.applicationSpec" (dict "namespace" "monitoring") | nindent 2 }}
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
  retry:
    limit: 5
    backoff:
      duration: 10s
      factor: 2
      maxDuration: 3m
{{- end -}}
