const updateOperationSidebarLabels = () => {
    document.querySelectorAll('.sidebar a[href^="#operation-"]').forEach((link) => {
        const operation = document.querySelector(link.getAttribute('href'));
        const destination = operation?.querySelector('h3 span.font-mono.text-base')?.textContent?.trim();
        const label = link.querySelector('span.break-all.inline-block');
        const displayLabel = destination;

        if (displayLabel && label && label.textContent !== displayLabel) {
            label.textContent = displayLabel;
        }
    });
};

updateOperationSidebarLabels();

new MutationObserver(updateOperationSidebarLabels).observe(document.getElementById('root'), {
    childList: true,
    subtree: true,
});
