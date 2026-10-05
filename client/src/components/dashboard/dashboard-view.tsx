import './style/dashboard.scss';

const DashboardView = () => {

    const getCurrentGreeting = () => {
        const currentHour = new Date().getHours();

        if (currentHour < 12) {
            return 'Good morning!';
        } else if (currentHour < 18) {
            return 'Good afternoon!';
        } else {
            return 'Good evening!';
        }
    };

    const suggestions = [
        'Why did payment failures increase after the latest deployment?',
        'What changed in the checkout service recently?',
        'Which Jira ticket caused this code change?',
        'Has this incident happened before?',
        'Show me the commits related to this issue.',
    ];

    return (
        <div className="dashboard-container">

            <div className="dashboard-header">
                <h1 className="greetings">
                    {getCurrentGreeting()}
                </h1>

                <p className="sub-title">
                    What would you like to investigate?
                </p>
            </div>

            <div>
                <div className="suggestions-container">
                    {suggestions.map((suggestion, index) => (
                        <div
                            key={index}
                            className="suggestion-button"
                        >
                            {suggestion}
                        </div>
                    ))}
                </div>
                {/* Investigation Box */}
                <div className="investigation-container">

                <textarea
                    className="investigation-textarea"
                    placeholder="Ask a question about your code, incidents, deployments or engineering systems..."
                />

                    <div className="investigation-divider"/>

                    <div className="investigation-footer">

                        <div className="tools-section">
                            <div className="tools-text">
                                Connect AI picks the relevant tools:
                            </div>

                            <div className="tool-badges">
                            <span className="tool-badge github">
                                GH
                            </span>

                                <span className="tool-badge jira">
                                JI
                            </span>

                                <span className="tool-badge splunk">
                                SP
                            </span>

                                <span className="tool-badge slack">
                                SL
                            </span>
                            </div>
                        </div>

                        {/* Investigate Button */}
                        <button
                            type="button"
                            className="investigate-button"
                        >
                            Investigate
                        </button>

                    </div>
                </div>


            </div>

        </div>
    );
};

export default DashboardView;