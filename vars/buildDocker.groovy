/**
 * Shared library function to build and push Docker images.
 *
 * Usage in Jenkinsfile:
 *   buildDocker(
 *     registry: 'registry.company.com',
 *     imageName: 'my-app',
 *     tag: env.BUILD_NUMBER,
 *     credentialsId: 'docker-registry-creds',
 *     dockerfile: 'Dockerfile',
 *     buildArgs: ['NODE_ENV=production', 'API_URL=https://api.company.com']
 *   )
 */
def call(Map config = [:]) {
    def registry = config.registry ?: 'registry.company.com'
    def imageName = config.imageName ?: error('imageName is required')
    def tag = config.tag ?: env.BUILD_NUMBER
    def credentialsId = config.credentialsId ?: 'docker-registry-creds'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def context = config.context ?: '.'
    def buildArgs = config.buildArgs ?: []

    def fullImage = "${registry}/${imageName}:${tag}"
    def latestImage = "${registry}/${imageName}:latest"

    echo "Building Docker image: ${fullImage}"

    // Build args string
    def buildArgsStr = buildArgs.collect { "--build-arg ${it}" }.join(' ')

    // Build
    sh "docker build -t ${fullImage} -t ${latestImage} -f ${dockerfile} ${buildArgsStr} ${context}"

    // Push
    withCredentials([usernamePassword(
        credentialsId: credentialsId,
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )]) {
        sh "echo ${DOCKER_PASS} | docker login ${registry} -u ${DOCKER_USER} --password-stdin"
        sh "docker push ${fullImage}"
        sh "docker push ${latestImage}"
    }

    echo "Pushed ${fullImage} and ${latestImage}"
    return fullImage
}
