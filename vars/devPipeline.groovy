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
    pipeline: devPipeline
spec:
  containers:
  - name: maven
    image: maven:3.6-jdk-11-slim
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: "/root/.m2"
      name: "m2repo"
      readOnly: false
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command:
    - cat
    tty: true
  volumes:
  - name: "m2repo"
    hostPath:
      path: "/home/ec2-user/.m2"
"""
			}
		}

		environment {
			VERSION=readMavenPom().getVersion()
			DOCKER_CREDS=credentials('docker')
			COMMITTER_EMAIL="""${sh(returnStdout: true, script: "git show -s --format='%ae' $GIT_COMMIT").trim()}"""
		}

		stages {
			
			stage('Deploy') {
				steps {
					container('kubectl') {
						script {
							withKubeConfig([credentialsId: 'kube-admin', serverUrl: 'http://aa2e7b27c1cd44b91be7df2d25925337-1841660522.us-east-1.elb.amazonaws.com']) {
								def NS = sh(returnStdout: true, script: "echo $COMMITTER_EMAIL | sed 's|@.*||' | sed 's|\\.|-|g'").trim()

								sh "sed -i 's|APP_NAME|${pipelineParams.app_name}|g' deployment.yaml"
								sh "sed -i 's|SERVICE_NAME|${pipelineParams.service_name}|g' deployment.yaml"
								sh "sed -i 's|DOCKER_USER|${pipelineParams.docker_user}|' deployment.yaml"
								sh "sed -i 's|SERVICE_PORT|${pipelineParams.service_port}|g' deployment.yaml"
								sh "sed -i 's|SERVICE_CONTEXT|${pipelineParams.service_context}|' deployment.yaml"
								sh "sed -i 's|LIVENESS_URL|${pipelineParams.liveness_url}|g' deployment.yaml"
								sh "sed -i 's|READINESS_URL|${pipelineParams.readiness_url}|g' deployment.yaml"
								sh "sed -i 's|:latest|:${VERSION}|' deployment.yaml"
								sh "sed -i 's|NAMESPACE|$NS|g' deployment.yaml"
								
								sh "cat deployment.yaml"
								//sh "kubectl apply -f deployment.yaml"
							}
						}
					}
				}
			}
		}
	}
}
