{{/*
Common workload templates for the CORTEX app charts. The rendered resource
names intentionally default to the chart name, e.g. cortex-gateway, matching
the P10 Docker DNS contract and the P11 Kubernetes Service/Deployment names.
*/}}

{{- define "cortex-common.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cortex-common.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "cortex-common.name" . -}}
{{- end -}}
{{- end -}}

{{- define "cortex-common.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cortex-common.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cortex-common.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "cortex-common.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: cortex
{{- with .Values.global.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "cortex-common.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "cortex-common.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "cortex-common.serviceAccount" -}}
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "cortex-common.serviceAccountName" . }}
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
automountServiceAccountToken: {{ .Values.serviceAccount.automount }}
{{- end }}
{{- end -}}

{{- define "cortex-common.configMap" -}}
{{- if .Values.env }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "cortex-common.fullname" . }}-env
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
data:
{{- range $key, $value := .Values.env }}
  {{ $key }}: {{ $value | quote }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "cortex-common.secret" -}}
{{- if .Values.secretEnv }}
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "cortex-common.fullname" . }}-secret
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
type: Opaque
stringData:
{{- range $key, $value := .Values.secretEnv }}
  {{ $key }}: {{ $value | quote }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "cortex-common.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "cortex-common.fullname" . }}
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "cortex-common.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "cortex-common.selectorLabels" . | nindent 8 }}
        {{- with .Values.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
        {{- with .Values.global.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      annotations:
        checksum/config-env: {{ toJson .Values.env | sha256sum }}
        checksum/secret-env: {{ toJson .Values.secretEnv | sha256sum }}
        {{- with .Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
        {{- with .Values.global.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      {{- $global := default dict .Values.global -}}
      {{- $pullSecrets := concat (default (list) $global.imagePullSecrets) (default (list) .Values.imagePullSecrets) -}}
      {{- if $pullSecrets }}
      imagePullSecrets:
        {{- toYaml $pullSecrets | nindent 8 }}
      {{- end }}
      serviceAccountName: {{ include "cortex-common.serviceAccountName" . }}
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: {{ include "cortex-common.name" . }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          ports:
            - name: http
              containerPort: {{ .Values.service.targetPort | default .Values.service.port }}
              protocol: TCP
          {{- if or .Values.env .Values.secretEnv .Values.envFrom }}
          envFrom:
            {{- if .Values.env }}
            - configMapRef:
                name: {{ include "cortex-common.fullname" . }}-env
            {{- end }}
            {{- if .Values.secretEnv }}
            - secretRef:
                name: {{ include "cortex-common.fullname" . }}-secret
            {{- end }}
            {{- with .Values.envFrom }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          {{- end }}
          {{- if .Values.probes.startup.enabled }}
          startupProbe:
            httpGet:
              path: {{ .Values.probes.startup.path }}
              port: http
            periodSeconds: {{ .Values.probes.startup.periodSeconds }}
            timeoutSeconds: {{ .Values.probes.startup.timeoutSeconds }}
            failureThreshold: {{ .Values.probes.startup.failureThreshold }}
          {{- end }}
          {{- if .Values.probes.liveness.enabled }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: http
            initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
            timeoutSeconds: {{ .Values.probes.liveness.timeoutSeconds }}
            failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
          {{- end }}
          {{- if .Values.probes.readiness.enabled }}
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: http
            initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
            timeoutSeconds: {{ .Values.probes.readiness.timeoutSeconds }}
            failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
          {{- end }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}

{{- define "cortex-common.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "cortex-common.fullname" . }}
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
  {{- with .Values.service.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
  selector:
    {{- include "cortex-common.selectorLabels" . | nindent 4 }}
{{- end -}}

{{- define "cortex-common.ingress" -}}
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "cortex-common.fullname" . }}
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- with .Values.ingress.className }}
  ingressClassName: {{ . }}
  {{- end }}
  {{- with .Values.ingress.tls }}
  tls:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  rules:
    {{- range .Values.ingress.hosts }}
    - host: {{ .host | quote }}
      http:
        paths:
          {{- range .paths }}
          - path: {{ .path }}
            pathType: {{ .pathType }}
            backend:
              service:
                name: {{ include "cortex-common.fullname" $ }}
                port:
                  name: http
          {{- end }}
    {{- end }}
{{- end }}
{{- end -}}

{{- define "cortex-common.hpa" -}}
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "cortex-common.fullname" . }}
  labels:
    {{- include "cortex-common.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "cortex-common.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    {{- if .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
    {{- end }}
    {{- if .Values.autoscaling.targetMemoryUtilizationPercentage }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetMemoryUtilizationPercentage }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "cortex-common.workload" -}}
{{- if .Values.serviceAccount.create }}
{{ include "cortex-common.serviceAccount" . }}
---
{{- end }}
{{- if .Values.env }}
{{ include "cortex-common.configMap" . }}
---
{{- end }}
{{- if .Values.secretEnv }}
{{ include "cortex-common.secret" . }}
---
{{- end }}
{{ include "cortex-common.deployment" . }}
---
{{ include "cortex-common.service" . }}
{{- if .Values.ingress.enabled }}
---
{{ include "cortex-common.ingress" . }}
{{- end }}
{{- if .Values.autoscaling.enabled }}
---
{{ include "cortex-common.hpa" . }}
{{- end }}
{{- end -}}
