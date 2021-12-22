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

                                                           if (BRANCH_NAME == '-main' || BRANCH == '-master') {
                                                              IMAGE_NAME = APP_NAME
                                                           }  else {
                                                              IMAGE_NAME = APP_NAME+BRANCH
                                                           }
                                                           sh "apk add curl"

                                                           sh "kubectl version"
                                                           sh "curl -sL https://git.io/getLatestIstio | sh -"
                                                           sh "ISTIO_VERSION=\$(curl -sL https://github.com/istio/istio/releases | grep -o 'releases/[0-9]*.[0-9]*.[0-9]*/' | sort -V | tail -1 | awk -F'/' '{ print \$2}')"
                                                           sh "cp istio-\$ISTIO_VERSION/bin/istioctl /usr/local/bin"
                                                           sh "istioctl version"
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
