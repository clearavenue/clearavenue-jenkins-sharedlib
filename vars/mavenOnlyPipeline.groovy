def call(body) {
	def pipelineParams = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = pipelineParams
	body()

	pipeline {
		agent {
			kubernetes {
				yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    pipeline: mavenDevsecopsPipeline
spec:
  containers:
  - name: maven
    image: maven:3.6-jdk-11-slim
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:v1.19.9
    command:
    - cat
    tty: true
  - name: jnlp
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi
"""
			}
		}

		environment {
			POM_VERSION=readMavenPom().getVersion()
                        DOCKER_CREDS=credentials('docker')
			BRANCH = env.GIT_BRANCH.toLowerCase()
		}

		stages {

                        stage('Deploy') {
                                steps {
                                        container('kubectl') {
                                                script {
                                                        withKubeConfig([credentialsId: 'jenkins-serviceaccount', serverUrl: 'https://2176A80F2DE9138595ADC878309B7CEC.gr7.us-east-1.eks.amazonaws.com']) {
                                                           APP_NAME=pipelineParams.app_name
                                                           BRANCH="-"+BRANCH

                                                           if (BRANCH == '-main' || BRANCH == '-master') {
                                                              IMAGE_NAME = APP_NAME
                                                           }  else {
                                                              IMAGE_NAME = APP_NAME+BRANCH
                                                           }
                                                           sh "apk add curl"
                                                           sh "curl -sL https://git.io/getLatestIstio | sh -"
                                                           sh "cp istio-\$(curl -sL https://github.com/istio/istio/releases | grep -o 'releases/[0-9]*.[0-9]*.[0-9]*/' | sort -V | tail -1 | awk -F'/' '{ print \$2}')/bin/istioctl /usr/local/bin"

                                                           sh '''
                                                               cat << EOF > deploy.yaml
apiVersion: v1
kind: Namespace
metadata:
  labels:
    clearavenue.com/app: $IMAGE_NAME
    clearavenue.com/env: prod
    kubernetes.io/metadata.name: IMAGE_NAME
  name: ${IMAGE_NAME}
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${IMAGE_NAME}-service-account
  namespace: ${IMAGE_NAME}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  annotations:
    clearavenue.com/app: ${IMAGE_NAME}
    clearavenue.com/env: prod
  labels:
    app: ${IMAGE_NAME}
    app.kubernetes.io/instance: ${IMAGE_NAME}
    app.kubernetes.io/name: ${IMAGE_NAME}
    release: prod
    tier: web
    track: stable
  name: ${IMAGE_NAME}
  namespace: ${IMAGE_NAME}
spec:
  progressDeadlineSeconds: 600
  replicas: 1
  revisionHistoryLimit: 10
  selector:
    matchLabels:
      app: ${IMAGE_NAME}
      release: prod
      tier: web
      track: stable
  strategy:
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 25%
    type: RollingUpdate
  template:
    metadata:
      annotations:
        clearavenue.com/app: ${IMAGE_NAME}
        clearavenue.com/env: prod
      labels:
        app: ${IMAGE_NAME}
        app.kubernetes.io/instance: ${IMAGE_NAME}
        app.kubernetes.io/name: ${IMAGE_NAME}
        release: prod
        tier: web
        track: stable
    spec:
      serviceAccountName: ${IMAGE_NAME}-service-account
      containers:
      - image: ${DOCKER_CREDS_USR}/${IMAGE_NAME}:${POM_VERSION}
        imagePullPolicy: Always
        livenessProbe:
          failureThreshold: 3
          httpGet:
            path: /actuator/health
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 90
          periodSeconds: 10
          successThreshold: 1
          timeoutSeconds: 15
        name: ${IMAGE_NAME}
        ports:
        - containerPort: 8080
          name: web
          protocol: TCP
        readinessProbe:
          failureThreshold: 3
          httpGet:
            path: /actuator/health
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 5
          periodSeconds: 10
          successThreshold: 1
          timeoutSeconds: 3
        terminationMessagePath: /dev/termination-log
        terminationMessagePolicy: File
      dnsPolicy: ClusterFirst
      restartPolicy: Always
      schedulerName: default-scheduler
      terminationGracePeriodSeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  labels:
    app: ${IMAGE_NAME}
    app.kubernetes.io/instance: ${IMAGE_NAME}
    app.kubernetes.io/name: ${IMAGE_NAME}
    release: prod
    track: stable
  name: ${IMAGE_NAME}
  namespace: ${IMAGE_NAME}
spec:
  ports:
  - name: web
    port: 8080
    protocol: TCP
    targetPort: 8080
  selector:
    app: ${IMAGE_NAME}
    tier: web
    track: stable
  sessionAffinity: None
  type: ClusterIP
---
apiVersion: networking.istio.io/v1alpha3
kind: Gateway
metadata:
  name: ${IMAGE_NAME}
  namespace: ${IMAGE_NAME}
spec:
  selector:
    istio: ingressgateway # use istio default controller
  servers:
  - port:
      number: 443
      name: https
      protocol: HTTPS
    tls:
       mode: SIMPLE
       credentialName: istio-ingressgateway-certs
    hosts:
    - "${IMAGE_NAME}.devsecops.clearavenue.com"
---
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: ${IMAGE_NAME}
  namespace: ${IMAGE_NAME}
spec:
  hosts:
  - "${IMAGE_NAME}.devsecops.clearavenue.com"
  gateways:
  - ${IMAGE_NAME}
  http:
  - route:
    - destination:
        host: ${IMAGE_NAME}
        port:
          number: 8080
                                                               EOF
                                                              '''

                                                              sh "cat deploy.yaml"

                                                               
                                                        }
                                                }
                                        }
                                }
                        }

		}

		post {
			always {
				emailext attachLog: true, subject: '$DEFAULT_SUBJECT', body: '$DEFAULT_CONTENT', recipientProviders: [
					[$class: 'CulpritsRecipientProvider'],
					[$class: 'DevelopersRecipientProvider'],
					[$class: 'RequesterRecipientProvider']
				]
			}
		}
	}
}
