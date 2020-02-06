Sample Jenkinsfile

```
@Library('clearavenue/clearavenue-jenkins-sharedlib') _
  mavenDevsecopsPipeline {
  project_name = fdadi-frontend
  docker_user = clearavenuedocker
  deployment_yaml = fdadi-frontend-deployment.yaml
}
```

Note:  Don't forget the '_' at the end of the @Library line !!!

**Any time the cluster url changes, these share lib pipeline groovy files will need updated**


Sample pod definitions for use in pipeline
```
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
    - mountPath: "/root/.m2"
      name: "m2repo"
      readOnly: false
  - name: docker
    image: docker
    command:
    - cat
    tty: true
    volumeMounts:
    - name: "volume-0"
      mountPath: "/var/run/docker.sock"
      readOnly: false
    - name: "m2repo"
      mountPath: "/root/.m2"
      readOnly: false
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command:
    - cat
    tty: true
    volumeMounts:
    - name: "volume-0"
      mountPath: "/var/run/docker.sock"
      readOnly: false
  volumes:
  - name: "volume-0"
    hostPath:
      path: "/var/run/docker.sock"
  - name: "m2repo"
    hostPath:
      path: "/home/ec2-user/.m2"

```
