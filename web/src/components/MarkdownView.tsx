import React from 'react';

interface MarkdownViewProps {
  content: string;
}

export const MarkdownView: React.FC<MarkdownViewProps> = ({ content }) => {
  const lines = content.split('\n');

  const renderFormattedText = (text: string) => {
    // Replace **bold**
    const parts = text.split(/(\*\*.*?\*\*)/g);
    return parts.map((part, index) => {
      if (part.startsWith('**') && part.endsWith('**')) {
        return <strong key={index}>{part.slice(2, -2)}</strong>;
      }
      return part;
    });
  };

  return (
    <div style={{ lineHeight: 1.6, fontSize: 14, color: '#1f2937' }}>
      {lines.map((line, idx) => {
        const trimmed = line.trim();

        // Empty line
        if (!trimmed) {
          return <div key={idx} style={{ height: 8 }} />;
        }

        // H3: ###
        if (trimmed.startsWith('### ')) {
          return (
            <h3 key={idx} style={{ fontSize: 16, fontWeight: 'bold', margin: '14px 0 6px', color: '#111827' }}>
              {trimmed.replace('### ', '')}
            </h3>
          );
        }

        // H2: ##
        if (trimmed.startsWith('## ')) {
          return (
            <h2
              key={idx}
              style={{
                fontSize: 17,
                fontWeight: 'bold',
                margin: '18px 0 8px',
                color: '#1e3a8a',
                borderBottom: '1px solid #e2e8f0',
                paddingBottom: 4,
              }}
            >
              {trimmed.replace('## ', '')}
            </h2>
          );
        }

        // H1: #
        if (trimmed.startsWith('# ')) {
          return (
            <h1 key={idx} style={{ fontSize: 19, fontWeight: 'bold', margin: '20px 0 10px', color: '#1e3a8a' }}>
              {trimmed.replace('# ', '')}
            </h1>
          );
        }

        // Bullet point: - or *
        if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
          return (
            <div key={idx} style={{ display: 'flex', gap: 6, margin: '4px 0', paddingLeft: 10 }}>
              <span style={{ color: '#2563eb' }}>•</span>
              <span style={{ flex: 1 }}>{renderFormattedText(trimmed.replace(/^[-*]\s+/, ''))}</span>
            </div>
          );
        }

        // Numbered list: 1. 2.
        const numMatch = trimmed.match(/^(\d+)\.\s+(.*)/);
        if (numMatch) {
          return (
            <div key={idx} style={{ display: 'flex', gap: 6, margin: '4px 0', paddingLeft: 10 }}>
              <span style={{ fontWeight: 'bold', color: '#2563eb', minWidth: 18 }}>{numMatch[1]}.</span>
              <span style={{ flex: 1 }}>{renderFormattedText(numMatch[2])}</span>
            </div>
          );
        }

        // Regular paragraph
        return (
          <p key={idx} style={{ margin: '4px 0' }}>
            {renderFormattedText(trimmed)}
          </p>
        );
      })}
    </div>
  );
};
