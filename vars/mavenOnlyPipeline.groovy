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
                        IMAGE_NAME = "dummy"
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
                                                           
                                                           sh "echo [$APP_NAME] [$BRANCH] [$IMAGE_NAME] [$POM_VERSION]"
                                                           sh "apk add curl"
                                                           sh "curl -sL https://git.io/getLatestIstio | sh -"
                                                           sh "cp istio-\$(curl -sL https://github.com/istio/istio/releases | grep -o 'releases/[0-9]*.[0-9]*.[0-9]*/' | sort -V | tail -1 | awk -F'/' '{ print \$2}')/bin/istioctl /usr/local/bin"

                                                           sh "curl https://raw.githubusercontent.com/clearavenue/clearavenue-jenkins-sharedlib/main/deploy.yaml > deploy.yaml"
                                                           sh "sed -i \"s|APP_NAME|$APP_NAME|g\" deploy.yaml"
                                                           sh "sed -i \"s|-BRANCH|$BRANCH|g\" deploy.yaml
                                                           sh "sed -i \"s|-ENV|$ENV|g\" deploy.yaml
                                                           sh "sed -i \"s|ENV|$3|g\" deploy.yaml
                                                           sh "sed -i \"s|VERSION|$4|g\" deploy.yaml

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
