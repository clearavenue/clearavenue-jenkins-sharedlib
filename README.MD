This sharedlib contains various pipelines for use in Jenkins.

Sample Jenkinsfile

```
@Library('clearavenue/clearavenue-jenkins-sharedlib')_

mavenDevsecopsPipeline {
  app_name = 'fdadi'
  docker_user = 'clearavenuedocker'
}
```

Note:  Don't forget the '_' at the end of the @Library line !!!

