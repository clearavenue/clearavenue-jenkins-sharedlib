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
    pipeline: rrsPipeline
spec:
  containers:
  - name: owasp
    image: owasp/zap2docker-stable:latest
    securityContext:
      privileged: true
    command:
    - cat
    tty: true
    volumeMounts:
    - name: zap-report-volume
      mountPath: /zap/wrk/
      readOnly: false

  - name: jhipster
    image: jhipster/jhipster:v7.6.0
    securityContext:
      privileged: true
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi

  - name: nodejs
    image: node:latest
    securityContext:
      privileged: true
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi

  - name: jnlp
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi

  - name: maven
    image: maven:3.6-jdk-11-slim
    securityContext:
      privileged: true
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi

  - name: git
    image: bitnami/git:latest
    command:
    - cat
    tty: true
    resources:
      requests:
        ephemeral-storage: 1Gi
      limits:
        ephemeral-storage: 5Gi

  volumes:
  - name: zap-report-volume
    emptyDir: {}
"""
			}
		}

		environment {
			POM_VERSION=readMavenPom().getVersion()
			BUILD_NUM=currentBuild.getNumber()
			DOCKER_CREDS=credentials('docker')
			GIT_CREDS=credentials('bill.hunt-github')
			BRANCH = env.GIT_BRANCH.toLowerCase()
			APP_BRANCH="dummy"

			WEB_APP="https://reservationapp.dev-devsecops.clearavenue.com"
			NPM_CONFIG_CACHE = "${WORKSPACE}/.npm"
		}

		stages {

			stage('E2E Test') {
				steps {
					container('nodejs'){
						sh '''
                               cd reservationapp
                                npm test
                        '''
					}
				}
			} // end selenium tests
		} // end stages

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
