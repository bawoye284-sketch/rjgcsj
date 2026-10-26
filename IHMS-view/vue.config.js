module.exports = {
    lintOnSave: false,
    devServer: {
        host: "localhost",
        port: Number(process.env.IHMS_VIEW_PORT || 21091),
        https: false,
        proxy: "http://localhost:21090",
        overlay: {
            warning: false,
            errors: false
        },
    }
}
