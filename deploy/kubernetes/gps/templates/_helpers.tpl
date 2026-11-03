{{/* Labels every object of the application carries. */}}
{{- define "gps.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: gps
{{- end -}}

{{/* A GPS image of the current release: <registry>/gps/<name>:<tag>. */}}
{{- define "gps.image" -}}
{{- $tag := required "images.tag must name a published release (see scripts/publish-images.sh)" .root.Values.images.tag -}}
{{ printf "%s/gps/%s:%s" .root.Values.images.registry .name $tag }}
{{- end -}}

{{/* Pod-level hardening for the GPS containers, which never need root. */}}
{{- define "gps.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
{{- end -}}

{{/* A NetworkPolicy peer: pods of one GPS component. */}}
{{- define "gps.peer" -}}
- podSelector:
    matchLabels:
      app.kubernetes.io/name: {{ . }}
{{- end -}}
