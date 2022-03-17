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
			}   // end kubernetes
		}  // end agent

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
                                        ./mvnw -B -e -T 1C ${pipelineParams.buildProfile} org.owasp:dependency-check-maven:7.0.0:aggregate -Dformat=xml -DfailOnError=false -DassemblyAnalyzerEnabled=false" 
                                    """
					} // end container
				} // end steps
				post {
					always {
						dependencyCheckPublisher(failedTotalCritical : 100, unstableTotalCritical : 100)
					} // end always
				} // end post
			} // end vulnerabilities
	      }  // end stages
     } // end pipeline
} // end call
