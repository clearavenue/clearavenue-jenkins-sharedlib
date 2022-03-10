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

  - name: cypress
    image: cypress/base:latest
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

  volumes:
  - name: zap-report-volume
    emptyDir: {}
"""
			}
		}

		environment {
			GIT_CREDS=credentials('bill.hunt-github')

			WEB_APP="${pipelineParams.webUrl}"
			
			NPM_CONFIG_CACHE="${WORKSPACE}/.npm"
			CYPRESS_CACHE_FOLDER="${WORKSPACE}/.cache/Cypress"
			CYPRESS_BASE_URL="${WEB_APP}"
			ELECTRON_ENABLE_LOGGING="false"
		}

		stages {

			stage('Jest Test') {
				steps {
					container('nodejs'){
						sh '''
                               cd reservationapp
                               npm install
                               npm test
                        '''
					}
				}
			} // end jest tests
			
			
			stage('Cypress Test') {
				steps {
					container('cypress'){
						sh '''
                               cd reservationapp
                               rm package-lock.json
                               npm install
                               npx cypress run -e E2E_ADMIN_PASSWORD=adminK3y#007,E2E_USER_PASSWORD=userK3y#007
                        '''
					}
				}
			} // end cypress tests
			
			
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
