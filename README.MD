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
