/**
 * Shared library function for Slack notifications.
 *
 * Usage:
 *   notifySlack(status: 'SUCCESS', channel: '#builds')
 *   notifySlack(status: 'FAILURE', channel: '#builds', message: 'Custom message')
 */
def call(Map config = [:]) {
    def status = config.status ?: currentBuild.currentResult
    def channel = config.channel ?: '#builds'
    def message = config.message ?: ''

    def color = 'warning'
    def emoji = ':question:'

    switch(status) {
        case 'SUCCESS':
            color = 'good'
            emoji = ':white_check_mark:'
            break
        case 'FAILURE':
            color = 'danger'
            emoji = ':x:'
            break
        case 'UNSTABLE':
            color = 'warning'
            emoji = ':warning:'
            break
    }

    def defaultMessage = "${emoji} *${env.JOB_NAME}* #${env.BUILD_NUMBER} - *${status}*\n" +
        "Branch: ${env.BRANCH_NAME ?: 'N/A'}\n" +
        "<${env.BUILD_URL}|View Build>"

    def finalMessage = message ?: defaultMessage

    slackSend(
        channel: channel,
        color: color,
        message: finalMessage
    )
}
