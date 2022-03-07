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
  - name: jhipster
    image: jhipster/jhipster:v7.6.0
    securityContext:
      runAsUser: 0
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
		}

		stages {
	
			stage('508 Test') {
				steps{
					container('jhipster'){
						sh '''
                apt-get install curl -y
                curl -sL https://deb.nodesource.com/setup_6.x | bash -
                apt-get install nodejs npm -y
                apt-get install gconf-service libasound2 libatk1.0-0 libatk-bridge2.0-0 libc6 libcairo2 libcups2 libdbus-1-3 libexpat1 libfontconfig1 libgcc1 libgconf-2-4 libgdk-pixbuf2.0-0 libglib2.0-0 libgtk-3-0 libnspr4 libpango-1.0-0 libpangocairo-1.0-0 libstdc++6 libx11-6 libx11-xcb1 libxcb1 libxcomposite1 libxcursor1 libxdamage1 libxext6 libxfixes3 libxi6 libxrandr2 libxrender1 libxss1 libxtst6 ca-certificates fonts-liberation libappindicator1 libnss3 lsb-release xdg-utils wget -y
                npm install -g pa11y-ci pa11y-ci-reporter-html --unsafe-perm=true
                pa11y-ci -c pa11y.json --json ${WEB_APP} | tee pa11y-ci-results.json
                pa11y-ci-reporter-html -d pa11y-html
              '''
						publishHTML target: [allowMissing: false, alwaysLinkToLastBuild: true, keepAll: true,
							reportDir: 'pa11y-html',
							reportFiles: 'index.html',
							reportName: "508 Test"
						]
					}
				}
			}   // end 508 tests
			
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
