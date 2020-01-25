def dockerImage = "clearavenuedocker/fdadi-frontend"
def builtImg = ''
def SERVER_URL="http://a3fd36bd80a9747afb9405ff3ad944b2-354023137.us-east-1.elb.amazonaws.com"

pipeline {
  agent {
    kubernetes {
      yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    some-label: some-label-value
spec:
  containers:
  - name: maven
    image: maven:3.6-jdk-11-slim
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: "/var/run/docker.sock"
      name: "volume-0"
      readOnly: false
  - name: docker
    image: docker
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: "/var/run/docker.sock"
      name: "volume-0"
      readOnly: false
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: "/var/run/docker.sock"
      name: "volume-0"
      readOnly: false
  volumes:
  - hostPath:
      path: "/var/run/docker.sock"
    name: "volume-0"
"""
    }
  }
  
  environment {
    VERSION=readMavenPom().getVersion()
  }
  
  stages {
    
    stage('Build') {
      parallel {
        stage('Build code') {
          steps {
            container('docker') {
              sh "docker build -t ${dockerImage}:${VERSION} ."
            }
          }
        }
        
        stage('Checkstyle code') {
          steps {
            container('maven') {
              sh "mvn -B -e -T 1C org.apache.maven.plugins:maven-checkstyle-plugin:3.1.0:checkstyle -Dcheckstyle.config.location=google_checks.xml"
            }    
          }
          post {
            always {
              recordIssues(enabledForFailure: false, tool: checkStyle(pattern: 'target/checkstyle-result.xml'))
            }
          }
        }                
      }
    }
    
    stage('JUnit') {
      steps {
        container('maven') {
          sh "mvn -B -e -T 1C test"
          junit 'target/surefire-reports/**/*.xml'
        }
      }
    }
    
    stage('DevSecOps') {
      parallel {
        stage('CodeCoverage') {
          steps {
            container('maven') {
              sh "mvn -B -e -T 1C org.jacoco:jacoco-maven-plugin:0.8.4:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.4:report"
              jacoco(execPattern: 'target/jacoco.exec', classPattern: 'target/classes', sourcePattern: 'src/main/java', exclusionPattern: 'src/test*', changeBuildStatus: true, 
                 minimumInstructionCoverage : '30', maximumInstructionCoverage : '31',
                 minimumBranchCoverage : '30', maximumBranchCoverage : '31',
                 minimumComplexityCoverage : '30', maximumComplexityCoverage : '31',
                 minimumLineCoverage : '30', maximumLineCoverage : '31',
                 minimumMethodCoverage : '30', maximumMethodCoverage : '31',
                 minimumClassCoverage : '30', maximumClassCoverage : '31')
            }
          }
        }
         
        stage('SpotBugs') {
          steps {
            container('maven') {
              sh "mvn -B -e -T 1C com.github.spotbugs:spotbugs-maven-plugin:3.1.12.2:check -Dspotbugs.effort=Max -Dspotbugs.threshold=Low"
            }
          }
          post {
            always {
              recordIssues(enabledForFailure: true, tool: spotBugs())
            }
          }
        }
        
        stage('PMD') {
          steps {
            container('maven') {
              sh "mvn -B -e org.apache.maven.plugins:maven-jxr-plugin:3.0.0:jxr org.apache.maven.plugins:maven-pmd-plugin:3.12.0:pmd"
            }
          }
          post {
            always {
              recordIssues(enabledForFailure: true, tool: pmdParser(pattern: 'target/pmd.xml'))
            }
          }
        }
    
        stage('Vulnerabilities') {
          steps {
            container('maven') {
              sh "mvn -B -e -T 1C org.owasp:dependency-check-maven:5.3.0:aggregate -Dformat=xml"
            }
          }
          post {
            always {
              dependencyCheckPublisher(failedTotalCritical : 100, unstableTotalCritical : 100)
            }
          }
        }
      }
    }

    stage('Push Docker') {
      when {
        expression { currentBuild.result == 'SUCCESS' }
      }
      steps {
	    container('docker') {
	      script {
            docker.withRegistry('', 'docker') {
	          sh "docker push ${dockerImage}:${VERSION}"
	          sh "docker rmi ${dockerImage}:${VERSION}"
	        }                           
	      }
	    }
	  }
	}
    
    stage('Deploy') {
      when {
        expression { currentBuild.result == 'SUCCESS' }
      }
	  steps {
 	    container('kubectl') {
 	      script {
	        withKubeConfig([credentialsId: 'kube-admin', serverUrl: '${SERVER_URL}']) {
	          sh "kubectl apply -f fdadi-frontend-deployment.yaml"
	        }
	      }      
	    }
	  }
	}
  }
    
  post {
    always {
        emailext attachLog: true, subject: '$DEFAULT_SUBJECT', body: '$DEFAULT_CONTENT', recipientProviders: [[$class: 'CulpritsRecipientProvider'],[$class: 'DevelopersRecipientProvider'],[$class: 'RequesterRecipientProvider']]
    }
  }
}
