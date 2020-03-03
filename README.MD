Sample Jenkinsfile

```
Library('clearavenue/clearavenue-jenkins-sharedlib')_

mavenDevsecopsPipeline {
  app_name = 'fdadi'
  docker_user = 'clearavenuedocker'
  service_name = 'fdadi-user-service'
  service_port = 8082
  liveness_url = '/actuator/health'
  readiness_url = '/actuator/health'
  deploymentFile = 'internal-deployment.yaml'
}
```

Note:  Don't forget the '_' at the end of the @Library line !!!

**Any time the cluster url changes, these share lib pipeline groovy files will need updated**


Sample pod definitions for use in pipeline
```
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
    volumeMounts:
    - mountPath: "/root/.m2"
      name: "m2repo"
      readOnly: false
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:v1.15.9
    command:
    - cat
    tty: true
  volumes:
  - name: "m2repo"
    hostPath:
      path: "/home/ec2-user/.m2"
```
