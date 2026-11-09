{{/*
Expand the name of the chart.
*/}}
{{- define "warp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
A fully qualified app name, truncated to 63 characters (DNS naming spec).
*/}}
{{- define "warp.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "warp.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "warp.labels" -}}
helm.sh/chart: {{ include "warp.chart" . }}
{{ include "warp.selectorLabels" . }}
app.kubernetes.io/version: {{ include "warp.appVersion" . | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: minecraft
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{/*
Selector labels: never change between releases.
*/}}
{{- define "warp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "warp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "warp.appVersion" -}}
{{- .Values.image.tag | default .Chart.AppVersion }}
{{- end }}

{{/*
The image reference: by digest when one is given, by tag otherwise.
*/}}
{{- define "warp.image" -}}
{{- if .Values.image.digest }}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.image.repository (include "warp.appVersion" .) }}
{{- end }}
{{- end }}

{{- define "warp.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "warp.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "warp.secretName" -}}
{{- .Values.secrets.existingSecret | default (include "warp.fullname" .) }}
{{- end }}

{{- define "warp.managementServiceName" -}}
{{- printf "%s-management" (include "warp.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
A Warp duration (200ms, 30s, 5m, 1h, 30d, or a bare 0) in whole seconds, rounded up.
*/}}
{{- define "warp.durationSeconds" -}}
{{- $text := toString . | trim -}}
{{- if eq $text "0" -}}
0
{{- else -}}
{{- $number := regexFind "^[0-9]+" $text -}}
{{- $unit := regexFind "[a-z]+$" $text -}}
{{- if or (eq $number "") (ne (printf "%s%s" $number $unit) $text) -}}
{{- fail (printf "not a duration: %q (write 200ms, 30s, 5m, 1h or 30d)" $text) -}}
{{- end -}}
{{- $n := int64 $number -}}
{{- if eq $unit "ms" -}}{{ div (add $n 999) 1000 }}
{{- else if eq $unit "s" -}}{{ $n }}
{{- else if eq $unit "m" -}}{{ mul $n 60 }}
{{- else if eq $unit "h" -}}{{ mul $n 3600 }}
{{- else if eq $unit "d" -}}{{ mul $n 86400 }}
{{- else -}}{{ fail (printf "not a duration: %q (write 200ms, 30s, 5m, 1h or 30d)" $text) }}
{{- end -}}
{{- end -}}
{{- end }}

{{/*
How long Kubernetes waits for a Pod to stop: the drain's propagation delay, its time-out and a
margin for the proxy to disable its plugins and exit.
*/}}
{{- define "warp.terminationGracePeriodSeconds" -}}
{{- $propagation := include "warp.durationSeconds" .Values.drain.propagationDelay | int64 -}}
{{- $timeout := include "warp.durationSeconds" .Values.drain.timeout | int64 -}}
{{- add $propagation $timeout (int64 .Values.drain.gracePeriodMargin) -}}
{{- end }}

{{/*
The scheme of the management probes.
*/}}
{{- define "warp.managementScheme" -}}
{{- if .Values.management.tls.enabled }}HTTPS{{ else }}HTTP{{ end }}
{{- end }}

{{/*
A HOCON string literal.
*/}}
{{- define "warp.hocon" -}}
{{- toJson (toString .) -}}
{{- end }}

{{/*
A HOCON list of strings.
*/}}
{{- define "warp.hoconList" -}}
{{- toJson (default (list) .) -}}
{{- end }}

{{/*
Validations shared by the templates.
*/}}
{{- define "warp.validate" -}}
{{- if and (not .Values.config.raw) (not .Values.config.servers) }}
{{- fail "config.servers needs at least one server (or set config.raw)" }}
{{- end }}
{{- if and .Values.config.proxyProtocol.enabled (not .Values.config.proxyProtocol.trustedProxies) }}
{{- fail "config.proxyProtocol.trustedProxies is required when the PROXY protocol is enabled" }}
{{- end }}
{{- if not (has .Values.drain.mode (list "sigterm" "preStop")) }}
{{- fail "drain.mode must be sigterm or preStop" }}
{{- end }}
{{- if and .Values.management.tls.enabled (not .Values.management.tls.secretName) }}
{{- fail "management.tls.secretName is required when management.tls.enabled" }}
{{- end }}
{{- if and .Values.podDisruptionBudget.enabled .Values.podDisruptionBudget.minAvailable .Values.podDisruptionBudget.maxUnavailable }}
{{- fail "podDisruptionBudget: set minAvailable or maxUnavailable, not both" }}
{{- end }}
{{- if eq (include "warp.durationSeconds" .Values.drain.timeout) "0" }}
{{- fail "drain.timeout must be more than zero (Warp refuses 0); to kick the players that cannot be transferred, set drain.cannotTransfer: kick" }}
{{- end }}
{{- if not .Values.secrets.existingSecret }}
{{- range .Values.management.console.tokens }}
{{- if not .generate }}
{{- fail (printf "management.console.tokens[%s]: the Deployment reads key %s from the chart's Secret, which only holds it with generate: true; set generate: true or secrets.existingSecret" .name .secretKey) }}
{{- end }}
{{- end }}
{{- end }}
{{- $monitor := .Values.management.metrics.serviceMonitor }}
{{- if and $monitor.enabled $monitor.namespace (ne $monitor.namespace .Release.Namespace) }}
{{- if or .Values.management.metrics.requireToken (and .Values.management.tls.enabled (not $monitor.insecureSkipVerify)) }}
{{- fail (printf "management.metrics.serviceMonitor.namespace: Prometheus Operator reads a ServiceMonitor's Secrets (the TLS Secret's ca.crt, the management token) from the ServiceMonitor's own namespace, and the chart creates them in %s only; leave namespace empty and let Prometheus select the ServiceMonitor by namespace (serviceMonitorNamespaceSelector), or turn requireToken off and insecureSkipVerify on" .Release.Namespace) }}
{{- end }}
{{- end }}
{{- end }}

{{/*
A Secret value: the one given in values, else the one the existing Secret holds (kept across
upgrades), else a new random one. Takes a dict: data (the existing Secret's), key, value.
*/}}
{{- define "warp.secretValue" -}}
{{- /* dict: data (existing), key, value (from values) */ -}}
{{- if .value }}{{ .value | b64enc }}
{{- else if hasKey .data .key }}{{ index .data .key }}
{{- else }}{{ randAlphaNum 48 | b64enc }}
{{- end }}
{{- end }}
