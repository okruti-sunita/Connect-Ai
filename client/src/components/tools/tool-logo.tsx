import type {CatalogItem} from './tool-catalog';

interface ToolLogoProps {
    name: string;
    item?: CatalogItem;
    size?: number;
}

const ToolLogo = ({name, item, size = 40}: ToolLogoProps) => {
    if (item?.logoUrl) {
        return <img className="tool-logo" src={item.logoUrl} alt="" width={size} height={size}/>;
    }
    const badge = item?.badge ?? (name.trim().slice(0, 2).toUpperCase() || '?');
    return (
        <span
            className="tool-logo"
            aria-hidden="true"
            style={{width: size, height: size, background: item?.color ?? '#475569', fontSize: Math.round(size * 0.34)}}
        >
            {badge}
        </span>
    );
};

export default ToolLogo;
