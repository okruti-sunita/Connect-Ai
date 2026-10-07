import * as React from "react";
import {useState} from "react";
import {
    Form,
    Input,
    Modal,
    Select,
    Switch
} from "antd";
import '../connections/style/connect-tool-modal.scss';

interface ToolConnectModalProps {
    open: boolean;
    onClose: () => void;
}

interface ToolFormValues {
    name: string;
    transport: string;
    endpoint: string;
    authType: "NONE" | "BEARER";
    secret?: string;
    enabled: boolean;
}

const ToolConnectModal = (props: ToolConnectModalProps) => {

    const [form] = Form.useForm<ToolFormValues>();

    const [authType, setAuthType] = useState<"NONE" | "BEARER">("NONE");

    const handleAuthTypeChange = (value: "NONE" | "BEARER") => {
        setAuthType(value);
        // Clear token when switching back to NONE
        if (value === "NONE") {
            form.setFieldValue("secret", undefined);
        }
    };

    const handleSubmit = async () => {
        try {
            const values = await form.validateFields();

            const payload = {
                name: values.name,
                transport: "STREAMABLE_HTTP",
                endpoint: values.endpoint,
                authType: values.authType,
                secret:
                    values.authType === "BEARER"
                        ? values.secret
                        : null,
                enabled: true
            };

            console.log("Register Tool Payload:", payload);

            await fetch(
                "http://localhost:8080/connect-ai/api/tools",
                {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify(payload)
                }
            );

            form.resetFields();
            setAuthType("NONE");
            props.onClose();

        } catch (error) {
            console.error("Error while registering tool:", error);
        }
    };

    const handleCancel = () => {
        form.resetFields();
        setAuthType("NONE");
        props.onClose();
    };

    return (
        <Modal
            open={props.open}
            title="Register New Tool"
            okText="Register Tool"
            cancelText="Cancel"
            onOk={handleSubmit}
            onCancel={handleCancel}
            centered
            className={'tool-register-modal'}
        >
            <Form
                form={form}
                layout="vertical"
                initialValues={{
                    authType: "NONE",
                    // enabled: true
                }}
            >

                <Form.Item
                    label="Name"
                    name="name"
                    rules={[
                        {
                            required: true,
                            message: "Please enter name"
                        }
                    ]}
                >
                    <Input placeholder="Enter name"/>
                </Form.Item>

                {/*<Form.Item*/}
                {/*    label="Transport"*/}
                {/*    name="transport"*/}
                {/*    rules={[*/}
                {/*        {*/}
                {/*            required: true,*/}
                {/*            message: "Please enter transport"*/}
                {/*        }*/}
                {/*    ]}*/}
                {/*>*/}
                {/*    <Input placeholder="Enter transport"/>*/}
                {/*</Form.Item>*/}

                <Form.Item
                    label="Endpoint"
                    name="endpoint"
                    rules={[
                        {
                            required: true,
                            message: "Please enter endpoint"
                        }
                    ]}
                >
                    <Input placeholder="Enter endpoint"/>
                </Form.Item>

                <Form.Item
                    label="Authentication Type"
                    name="authType"
                    rules={[
                        {
                            required: true,
                            message: "Please select authentication type"
                        }
                    ]}
                >
                    <Select
                        value={authType}
                        onChange={handleAuthTypeChange}
                        options={[
                            {
                                label: "None",
                                value: "NONE"
                            },
                            {
                                label: "Bearer",
                                value: "BEARER"
                            }
                        ]}
                    />
                </Form.Item>

                {authType === "BEARER" && (
                    <Form.Item
                        label="Enter Token"
                        name="secret"
                        rules={[
                            {
                                required: true,
                                message: "Please enter bearer token"
                            }
                        ]}
                    >
                        <Input.Password
                            placeholder="Enter bearer token"
                        />
                    </Form.Item>
                )}

                {/*<Form.Item*/}
                {/*    label="Enabled"*/}
                {/*    name="enabled"*/}
                {/*    valuePropName="checked"*/}
                {/*>*/}
                {/*    <Switch/>*/}
                {/*</Form.Item>*/}

            </Form>
        </Modal>
    );
};

export default ToolConnectModal;