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
    pipeline: cveCheckPipeline
spec:
  containers:
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
			BUILD_NUM=currentBuild.getNumber()
			DOCKER_CREDS=credentials('docker')
			GIT_CREDS=credentials('bill.hunt-github')
			BRANCH = env.GIT_BRANCH.toLowerCase()
		}

		stages {
			stage('Vulnerabilities') {
				steps {
					container('jhipster') {
      					sh """
                                        cd reservationapp
                                        npm install
                                        cd ../reservationservice
                                        npm install
                                        cd ..
                                        ./mvnw -B -e -T 1C ${BUILD_PROFILE} org.owasp:dependency-check-maven:7.0.0:aggregate -Dformat=xml -DfailOnError=false -DassemblyAnalyzerEnabled=false" 
                                    ""
					}
				}
				post {
					always {
						dependencyCheckPublisher(failedTotalCritical : 100, unstableTotalCritical : 100)
					}
				}
			} // end vulnerabilities
	      }
     }
}
